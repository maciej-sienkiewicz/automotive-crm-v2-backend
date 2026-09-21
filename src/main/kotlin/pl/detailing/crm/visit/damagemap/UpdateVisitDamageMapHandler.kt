package pl.detailing.crm.visit.damagemap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.checkin.qr.CheckinPhotoService
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.livemetrics.BusinessEventPublisher
import pl.detailing.crm.livemetrics.domain.BusinessEventType
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.DocumentType
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.visit.domain.DamagePoint
import pl.detailing.crm.visit.infrastructure.DamageMapReportService
import pl.detailing.crm.visit.infrastructure.DamageMarkingService
import pl.detailing.crm.visit.infrastructure.DamagePhotoAttachment
import pl.detailing.crm.visit.infrastructure.DocumentService
import pl.detailing.crm.visit.infrastructure.S3DamageMapStorageService
import pl.detailing.crm.visit.infrastructure.VisitDocumentRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

/**
 * Co zrobić z dotychczasowym PDF-em mapy uszkodzeń, gdy operator dorysował punkty.
 *
 * Nie jest to preferencja wizualna, tylko decyzja o dowodzie. Mapa z przyjęcia bywa
 * podpisana przez klienta i wysłana mailem — nadpisanie jej znaczy, że nie da się
 * już pokazać, co było widać przy przyjęciu, a co dopisano później.
 */
enum class DamageMapUpdateMode {
    /**
     * Nowy plik obok dotychczasowego: `damage-map-r2.pdf`, osobny wiersz w
     * dokumentach wizyty. Mapa z przyjęcia zostaje nietknięta.
     *
     * Domyślne i zalecane wszędzie, gdzie pierwszy dokument mógł już trafić do
     * klienta.
     */
    NEW_FILE,

    /**
     * Nadpisanie pliku pod tym samym kluczem S3 i podmiana wiersza w dokumentach.
     * W galerii zostaje jedna, aktualna mapa.
     *
     * Do poprawek jeszcze „ciepłych" — literówka w opisie, punkt postawiony obok.
     */
    REPLACE_EXISTING
}

/**
 * „Zaktualizuj uszkodzenia" z karty wizyty.
 *
 * Biznes zgłaszał to tak: w trakcie prac wychodzi rysa, której nie było w protokole
 * przyjęcia, i nie ma jej gdzie dopisać — mapa uszkodzeń powstawała raz, przy
 * przyjęciu, i od tego momentu była plikiem, nie danymi. Ten handler domyka pętlę:
 * bierze zapisane punkty ([VisitDamageMapStore]), przyjmuje nową wersję, generuje
 * PDF tą samą drogą co przyjęcie (więc dokument wygląda identycznie) i rejestruje
 * go w dokumentach wizyty.
 *
 * Kolejność kroków jest celowa: **najpierw punkty, potem plik**. Gdy generowanie
 * PDF-a padnie (brak fontu, S3 nie odpowiada), zaznaczenia operatora są już zapisane
 * i nikt nie musi klikać ich po raz drugi — plik da się wygenerować ponownie, pamięć
 * człowieka nie.
 *
 * Dlatego [handle] NIE jest `@Transactional`, choć wygląda na kandydata.
 * Jedna wspólna transakcja zamieniłaby tę obietnicę w pozór: `registerDocument`
 * ma własne `@Transactional`, więc wyjątek z niego oznaczyłby wspólną transakcję
 * jako rollback-only, a nasze `catch` tylko ukryłoby prawdę do momentu commitu
 * (`UnexpectedRollbackException`) — i punkty zniknęłyby razem z plikiem, dokładnie
 * wtedy, gdy najbardziej potrzebne. Każdy krok ma więc własną transakcję (zapis
 * punktów w [VisitDamageMapStore], rejestracja dokumentu w
 * [pl.detailing.crm.visit.infrastructure.DocumentService]), a kolejność wyżej
 * gwarantuje, że wcześniejszy krok przeżyje awarię późniejszego.
 */
@Service
class UpdateVisitDamageMapHandler(
    private val visitRepository: VisitRepository,
    private val visitDocumentRepository: VisitDocumentRepository,
    private val damageMapStore: VisitDamageMapStore,
    private val damageMapReportService: DamageMapReportService,
    private val damageMarkingService: DamageMarkingService,
    private val s3DamageMapStorageService: S3DamageMapStorageService,
    private val documentService: DocumentService,
    private val checkinPhotoService: CheckinPhotoService,
    private val customerRepository: CustomerRepository,
    private val notifier: VisitDamageMapNotifier,
    private val auditService: AuditService,
    private val businessEventPublisher: BusinessEventPublisher
) {
    companion object {
        private val logger = LoggerFactory.getLogger(UpdateVisitDamageMapHandler::class.java)

        /** Powyżej tego mapa przestaje być czytelna, a PDF — jednostronicowy. */
        const val MAX_DAMAGE_POINTS = 60

        /** Pojazd jest już wydany albo wizyty nie ma — mapa przestaje być zapisem stanu, a staje się dokumentem zamkniętym. */
        private val CLOSED_STATUSES = setOf(VisitStatus.COMPLETED, VisitStatus.REJECTED, VisitStatus.ARCHIVED)
    }

    suspend fun handle(command: UpdateVisitDamageMapCommand): UpdateVisitDamageMapResult {
        validate(command)

        val visitEntity = visitRepository.findByIdAndStudioIdWithPhotos(
            command.visitId.value, command.studioId.value
        ) ?: throw EntityNotFoundException("Visit not found: ${command.visitId}")

        /*
         * Wizyty zamkniętej i wydanej nie dopisujemy: mapa uszkodzeń jest wtedy
         * dokumentem odbioru, a dorysowanie punktu po wydaniu pojazdu znaczyłoby,
         * że studio jednostronnie zmienia stan rzeczy, którego nikt już nie może
         * obejrzeć. Korekta takiej wizyty to nowa wizyta albo wpis w komentarzu.
         */
        if (visitEntity.status in CLOSED_STATUSES) {
            throw ValidationException(
                "Mapy uszkodzeń nie można już zmienić — wizyta jest zamknięta. " +
                    "Nowe ustalenia zapisz w komentarzu do wizyty."
            )
        }

        val previous = damageMapStore.load(command.visitId, command.studioId)
        val pointsBefore = previous?.damagePoints?.size ?: 0
        val hadDocument = visitEntity.damageMapFileId != null

        // KROK 1 — punkty. Zapis przed generowaniem pliku, patrz nota klasy.
        val revision = damageMapStore.save(
            visitId = command.visitId,
            studioId = command.studioId,
            damagePoints = command.damagePoints,
            vehicleType = command.vehicleType,
            userId = command.userId,
            userName = command.userName,
            bumpRevision = true
        )

        // KROK 2 — plik. Świadomie „best effort": punkty już są, a brak PDF-a jest
        // odwracalny kolejnym zapisem.
        val generated = regenerateDocument(command, visitEntity, revision)

        // KROK 3 — audyt. Musi powstać także wtedy, gdy PDF się nie udał: ślad
        // „kto i kiedy dopisał uszkodzenie" jest ważniejszy od pliku.
        recordAudit(command, visitEntity.visitNumber, pointsBefore, generated, revision, hadDocument)

        // Live metrics — liczymy PONOWNE wypełnienie mapy uszkodzeń. Rewizja 1 powstaje przy
        // check-inie i jest częścią przyjęcia, więc pierwsze wypełnienie się nie liczy.
        if (revision > 1) {
            businessEventPublisher.publish(
                tenantId = command.studioId,
                type = BusinessEventType.DAMAGE_MAP_REFILLED,
                attributes = mapOf(
                    "visitId" to command.visitId.value.toString(),
                    "revision" to revision.toString(),
                    "pointsBefore" to pointsBefore.toString(),
                    "pointsAfter" to command.damagePoints.size.toString(),
                    "userId" to command.userId.value.toString()
                )
            )
        }

        // KROK 4 — klient. Tylko na wyraźne TAK.
        val notification = if (command.notifyCustomer) {
            notifyCustomer(command, visitEntity, pointsBefore, generated)
        } else null

        return UpdateVisitDamageMapResult(
            revision = revision,
            pointsCount = command.damagePoints.size,
            documentId = generated?.documentId,
            fileName = generated?.fileName,
            documentGenerated = generated != null,
            notification = notification
        )
    }

    private fun validate(command: UpdateVisitDamageMapCommand) {
        if (command.damagePoints.size > MAX_DAMAGE_POINTS) {
            throw ValidationException(
                "Mapa może zawierać najwyżej $MAX_DAMAGE_POINTS oznaczeń, przysłano ${command.damagePoints.size}"
            )
        }
        val duplicateIds = command.damagePoints
            .groupingBy { it.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateIds.isNotEmpty()) {
            throw ValidationException("Punkty mapy mają powtórzone numery: ${duplicateIds.sorted().joinToString(", ")}")
        }
        if (command.notifyMessage != null && command.notifyMessage.isBlank()) {
            throw ValidationException("Treść powiadomienia nie może być pusta — pomiń pole, żeby użyć tekstu domyślnego")
        }
    }

    /**
     * Generuje PDF z aktualnych punktów i podmienia/dokłada dokument wizyty.
     *
     * Zwraca null, gdy z mapy nie ma czego generować (zero punktów) albo gdy coś
     * padło po drodze. Nigdy nie rzuca — patrz nota klasy.
     */
    private suspend fun regenerateDocument(
        command: UpdateVisitDamageMapCommand,
        visitEntity: pl.detailing.crm.visit.infrastructure.VisitEntity,
        revision: Int
    ): GeneratedDocument? {
        if (command.damagePoints.isEmpty()) {
            logger.info(
                "Mapa uszkodzeń wyczyszczona do zera punktów [visit={}] — PDF nie jest generowany",
                command.visitId
            )
            return null
        }

        return try {
            val attachments = collectPhotoAttachments(command.damagePoints, visitEntity)
            val pdfBytes = damageMapReportService.generateReport(
                damagePoints = command.damagePoints,
                photoAttachments = attachments,
                vehicleType = command.vehicleType
            ) ?: return null

            /*
             * Tu leży cała różnica między dwiema opcjami z modala.
             *
             * NEW_FILE: klucz z sufiksem rewizji, obok dotychczasowego.
             * REPLACE_EXISTING: nadpisanie klucza AKTUALNEJ mapy wizyty, a nie
             *   kanonicznego `damage-map.pdf`. Gdyby zawsze szło pod kanoniczny, to
             *   druga „aktualizacja istniejącego" po wcześniejszym „nowym pliku"
             *   nadpisywałaby mapę Z PRZYJĘCIA — dokładnie ten dokument, którego
             *   ta opcja miała nie ruszać.
             */
            val s3Key = when (command.mode) {
                DamageMapUpdateMode.NEW_FILE -> s3DamageMapStorageService.uploadDamageMap(
                    studioId = command.studioId.value,
                    visitId = command.visitId.value,
                    pdfBytes = pdfBytes,
                    revisionSuffix = "r$revision"
                )
                DamageMapUpdateMode.REPLACE_EXISTING -> {
                    val target = visitEntity.damageMapFileId
                    if (target != null) {
                        s3DamageMapStorageService.uploadDamageMapToKey(target, pdfBytes)
                        target
                    } else {
                        s3DamageMapStorageService.uploadDamageMap(
                            studioId = command.studioId.value,
                            visitId = command.visitId.value,
                            pdfBytes = pdfBytes
                        )
                    }
                }
            }

            if (command.mode == DamageMapUpdateMode.REPLACE_EXISTING) {
                // Wiersz dokumentu jest niemutowalny (pola `val`), więc podmiana =
                // usunięcie starego wpisu i rejestracja nowego pod TYM SAMYM kluczem
                // S3. Pliku nie usuwamy: został właśnie nadpisany.
                replaceExistingDamageMapDocuments(command.visitId.value, s3Key)
            }

            val fileName = s3Key.substringAfterLast('/')
            val document = documentService.registerDocument(
                visitId = command.visitId.value,
                customerId = visitEntity.customerId,
                documentType = DocumentType.DAMAGE_MAP,
                name = damageMapDocumentName(visitEntity.visitNumber, revision, command.mode),
                s3Key = s3Key,
                fileName = fileName,
                createdBy = command.userId.value,
                createdByName = command.userName,
                category = "damage"
            )

            /*
             * `visits.damage_map_file_id` znaczy „aktualna mapa wizyty" — to jego
             * doklejają maile (SendVisitWelcomeEmailHandler) i on decyduje, co
             * zobaczy klient. Bez tej aktualizacji nowy plik istniałby w galerii, a
             * poczta wysyłałaby nadal wersję z przyjęcia.
             */
            visitRepository.updateDamageMapFileId(
                id = command.visitId.value,
                studioId = command.studioId.value,
                fileId = s3Key,
                userId = command.userId.value,
                now = Instant.now()
            )

            damageMapStore.save(
                visitId = command.visitId,
                studioId = command.studioId,
                damagePoints = command.damagePoints,
                vehicleType = command.vehicleType,
                documentS3Key = s3Key,
                userId = command.userId,
                userName = command.userName,
                bumpRevision = false
            )

            GeneratedDocument(
                documentId = document.id.value.toString(),
                fileName = fileName,
                s3Key = s3Key,
                pdfBytes = pdfBytes
            )
        } catch (e: Exception) {
            logger.error(
                "Nie udało się wygenerować mapy uszkodzeń po aktualizacji [visit={}]: {}",
                command.visitId, e.message, e
            )
            null
        }
    }

    private fun damageMapDocumentName(visitNumber: String, revision: Int, mode: DamageMapUpdateMode): String =
        if (mode == DamageMapUpdateMode.REPLACE_EXISTING) "Mapa uszkodzeń - $visitNumber"
        else "Mapa uszkodzeń (akt. $revision) - $visitNumber"

    private fun replaceExistingDamageMapDocuments(visitId: java.util.UUID, newS3Key: String) {
        val stale = visitDocumentRepository.findByVisit_IdOrderByUploadedAtDesc(visitId)
            .filter { it.type == DocumentType.DAMAGE_MAP && it.fileId == newS3Key }
        if (stale.isNotEmpty()) {
            // Usunięcie domyka własną transakcję repozytorium (brak transakcji
            // nadrzędnej — patrz nota klasy), więc rejestracja nowego wiersza niżej
            // nie zobaczy już starego i w galerii nie mrugną dwa wpisy do tego
            // samego pliku.
            visitDocumentRepository.deleteAll(stale)
        }
    }

    /**
     * Wypala zaznaczenia pisaka w zdjęcia przypisane do punktów i zwraca bajty do
     * wklejenia w PDF — ta sama droga, którą idzie przyjęcie
     * (`CreateVisitFromReservationHandler.processDamagePhotos`).
     *
     * Zdjęcia, których nie ma w galerii wizyty, są pomijane: punkt zostaje,
     * traci tylko ilustrację.
     */
    private suspend fun collectPhotoAttachments(
        damagePoints: List<DamagePoint>,
        visitEntity: pl.detailing.crm.visit.infrastructure.VisitEntity
    ): List<DamagePhotoAttachment> {
        if (damagePoints.none { it.photos.isNotEmpty() }) return emptyList()

        /*
         * Klucze S3 wyciągamy z encji TU, na wątku transakcji, i dalej pracujemy już
         * tylko na stringach. Kolekcja `photos` jest wprawdzie dociągnięta przez
         * JOIN FETCH, ale czytanie encji Hibernate z puli IO to zaproszenie na
         * LazyInitializationException przy pierwszej zmianie zapytania wyżej —
         * a takie błędy wychodzą na produkcji, nie w testach.
         *
         * Zdjęcie bywa adresowane identyfikatorem wiersza albo nazwą pliku z
         * uploadu QR ("{photoId}.jpg") — mapa przyjmuje oba warianty.
         */
        val s3KeyByPhotoKey = mutableMapOf<String, String>()
        visitEntity.photos.forEach { photo ->
            s3KeyByPhotoKey[photo.id.toString()] = photo.fileId
            s3KeyByPhotoKey[photo.fileName.substringBeforeLast('.')] = photo.fileId
        }
        val visitIdForLog = visitEntity.id

        return withContext(Dispatchers.IO) {
            val attachments = mutableListOf<DamagePhotoAttachment>()
            for (point in damagePoints) {
                for (damagePhoto in point.photos) {
                    try {
                        val s3Key = s3KeyByPhotoKey[damagePhoto.photoId] ?: continue
                        val originalBytes = checkinPhotoService.downloadPhotoBytes(s3Key) ?: continue
                        val bytes = if (damagePhoto.strokes.isNotEmpty()) {
                            damageMarkingService.annotatePhoto(originalBytes, damagePhoto.strokes)
                        } else {
                            originalBytes
                        }
                        attachments += DamagePhotoAttachment(
                            damagePointId = point.id,
                            note = point.note,
                            imageBytes = bytes,
                            // Zaznaczenia są już wypalone w bajtach powyżej.
                            strokes = emptyList()
                        )
                    } catch (e: Exception) {
                        logger.warn(
                            "Pomijam zdjęcie uszkodzenia [visit={} photo={}]: {}",
                            visitIdForLog, damagePhoto.photoId, e.message
                        )
                    }
                }
            }
            attachments
        }
    }

    private suspend fun recordAudit(
        command: UpdateVisitDamageMapCommand,
        visitNumber: String,
        pointsBefore: Int,
        generated: GeneratedDocument?,
        revision: Int,
        hadDocument: Boolean
    ) {
        val changes = listOf(
            FieldChange("damagePointsCount", pointsBefore.toString(), command.damagePoints.size.toString())
        )
        auditService.log(
            LogAuditCommand(
                studioId = command.studioId,
                userId = command.userId,
                userDisplayName = command.userName,
                module = AuditModule.VISIT,
                entityId = command.visitId.value.toString(),
                entityDisplayName = "Wizyta #$visitNumber",
                action = AuditAction.UPDATE,
                changes = changes,
                metadata = mapOf(
                    "damageMapRevision" to revision.toString(),
                    "damageMapMode" to command.mode.name,
                    "damagePointsCount" to command.damagePoints.size.toString(),
                    "damageMapDocumentGenerated" to (generated != null).toString(),
                    "damageMapHadDocumentBefore" to hadDocument.toString(),
                    "customerNotified" to command.notifyCustomer.toString()
                )
            )
        )
    }

    private fun notifyCustomer(
        command: UpdateVisitDamageMapCommand,
        visitEntity: pl.detailing.crm.visit.infrastructure.VisitEntity,
        pointsBefore: Int,
        generated: GeneratedDocument?
    ): DamageMapNotificationResult {
        val customer = customerRepository.findByIdAndStudioId(visitEntity.customerId, command.studioId.value)
        val vehicleLabel = listOfNotNull(
            visitEntity.brandSnapshot,
            visitEntity.modelSnapshot,
            visitEntity.licensePlateSnapshot?.let { "($it)" }
        ).joinToString(" ")

        return notifier.notifyCustomer(
            DamageMapNotificationRequest(
                studioId = command.studioId,
                visitId = command.visitId,
                visitNumber = visitEntity.visitNumber,
                customerId = CustomerId(visitEntity.customerId),
                customerFirstName = customer?.firstName,
                recipientEmail = customer?.email,
                recipientPhone = customer?.phone,
                vehicleLabel = vehicleLabel,
                pointsBefore = pointsBefore,
                pointsAfter = command.damagePoints.size,
                pdfBytes = generated?.pdfBytes,
                messageBody = command.notifyMessage
            )
        )
    }

    private data class GeneratedDocument(
        val documentId: String,
        val fileName: String,
        val s3Key: String,
        val pdfBytes: ByteArray
    )
}

data class UpdateVisitDamageMapCommand(
    val visitId: VisitId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String,
    val damagePoints: List<DamagePoint>,
    val vehicleType: String?,
    val mode: DamageMapUpdateMode,
    /** „Poinformuj klienta o zmianach" — TAK/NIE z ostatniego kroku modala. */
    val notifyCustomer: Boolean,
    /** Treść wpisana przez operatora; null = tekst domyślny. */
    val notifyMessage: String?
)

data class UpdateVisitDamageMapResult(
    val revision: Int,
    val pointsCount: Int,
    val documentId: String?,
    val fileName: String?,
    val documentGenerated: Boolean,
    val notification: DamageMapNotificationResult?
)
