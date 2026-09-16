package pl.detailing.crm.visit.damagemap

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.checkin.qr.AnnotationPointData
import pl.detailing.crm.checkin.qr.AnnotationStrokeData
import pl.detailing.crm.checkin.qr.CheckinDamagePointsService
import pl.detailing.crm.checkin.qr.CheckinPhotoService
import pl.detailing.crm.checkin.qr.DamagePointData
import pl.detailing.crm.checkin.qr.DamagePointPhotoData
import pl.detailing.crm.checkin.qr.UploadContextTokenService
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.visit.domain.DamagePoint
import pl.detailing.crm.visit.infrastructure.PhotoSessionService
import pl.detailing.crm.visit.infrastructure.VisitPhotoEntity
import pl.detailing.crm.visit.infrastructure.VisitPhotoRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

/**
 * Telefon jako narzędzie do mapy uszkodzeń OTWARTEJ wizyty.
 *
 * Operator stoi przy samochodzie, nie przy biurku — a rysa, którą trzeba dopisać, jest
 * widoczna z odległości pół metra. Ten serwis nie buduje drugiego kanału mobilnego,
 * tylko podłącza istniejący (ten od przyjęcia z kodem QR) do wizyty: token sesji jest
 * kluczowany opaque'owym `checkinId`, więc wystarczy podać tam identyfikator WIZYTY.
 * Strona mobilna, endpointy pod `/api/mobile/checkin` i zdarzenia WebSocket zostają bez
 * jednej zmiany.
 *
 * Model współpracy jest jednostronny i taki ma być: **telefon jest urządzeniem
 * wejściowym otwartego okna na komputerze**, nie drugim edytorem. Dlatego
 * [startSession] ZASIEWA sesję aktualnymi punktami — bez tego telefon zaczynałby od
 * pustej mapy i pierwszy zapis z telefonu skasowałby oznaczenia z przyjęcia. Do mapy
 * wizyty zapisuje wyłącznie komputer.
 */
@Service
class VisitDamageMapMobileService(
    private val visitRepository: VisitRepository,
    private val visitPhotoRepository: VisitPhotoRepository,
    private val uploadContextTokenService: UploadContextTokenService,
    private val checkinDamagePointsService: CheckinDamagePointsService,
    private val checkinPhotoService: CheckinPhotoService,
    private val photoSessionService: PhotoSessionService
) {
    companion object {
        private val logger = LoggerFactory.getLogger(VisitDamageMapMobileService::class.java)

        /** Te same statusy, których nie wpuszcza [UpdateVisitDamageMapHandler]. */
        private val CLOSED_STATUSES = setOf(VisitStatus.COMPLETED, VisitStatus.REJECTED, VisitStatus.ARCHIVED)
    }

    /**
     * Otwiera (albo odnawia) sesję mobilną dla wizyty i zwraca token do kodu QR.
     *
     * @param damagePoints punkty z otwartego edytora — telefon ma zacząć od nich,
     *        nie od pustej mapy.
     * @param rotate true unieważnia poprzedni kod QR; domyślnie ten sam token dostaje
     *        tylko odświeżony TTL, żeby telefon, który już zeskanował kod, nie wypadł
     *        z sesji przy ponownym otwarciu okna.
     */
    @Transactional(readOnly = true)
    fun startSession(
        visitId: VisitId,
        studioId: StudioId,
        userId: UserId,
        damagePoints: List<DamagePoint>,
        vehicleType: String?,
        rotate: Boolean
    ): MobileSessionToken {
        requireOpenVisit(visitId, studioId)

        val tenantId = studioId.value.toString()
        val checkinId = visitId.value.toString()

        /*
         * Klucze S3 zdjęć wizyty, żeby telefon pokazał MINIATURY już przypiętych
         * zdjęć, a nie puste kafelki. Sesja mobilna opisuje zdjęcie kluczem i z niego
         * podpisuje adres — a zdjęcie wizyty leży w tym samym koszyku, tylko pod inną
         * ścieżką. Bez tego telefon dostawał punkt z „jakimś zdjęciem", którego nie
         * umiał wyrenderować, i operator przy samochodzie nie wiedział, co już jest
         * udokumentowane.
         */
        val visitPhotoKeys = visitRepository
            .findByIdAndStudioIdWithPhotos(visitId.value, studioId.value)
            ?.photos
            ?.associate { it.id.toString() to it.fileId }
            .orEmpty()

        // Zasiew PRZED wydaniem tokena: telefon, który zeskanuje kod w tej samej
        // sekundzie, musi już zobaczyć pełną mapę.
        checkinDamagePointsService.saveDamagePoints(
            tenantId = tenantId,
            checkinId = checkinId,
            damagePoints = damagePoints.map { toSessionData(it, visitPhotoKeys) },
            vehicleType = vehicleType
        )

        val generated = uploadContextTokenService.generateToken(
            tenantId = tenantId,
            checkinId = checkinId,
            userId = userId.value.toString(),
            rotate = rotate
        )

        logger.info(
            "Damage-map mobile session ready [visit={} points={} rotate={}]",
            visitId, damagePoints.size, rotate
        )
        return MobileSessionToken(
            token = generated.token,
            checkinId = checkinId,
            expiresAt = generated.expiresAt
        )
    }

    /**
     * Co telefon zdążył zaznaczyć. `null`, gdy żadnej sesji nie ma — po odświeżeniu
     * strony okno musi umieć dociągnąć pracę z telefonu, której nie zobaczyło po
     * WebSockecie.
     */
    @Transactional(readOnly = true)
    fun readSession(visitId: VisitId, studioId: StudioId): MobileSessionState? {
        val tenantId = studioId.value.toString()
        val checkinId = visitId.value.toString()

        uploadContextTokenService.getTokenForCheckin(tenantId, checkinId) ?: return null

        val result = checkinDamagePointsService.getDamagePoints(tenantId, checkinId)
        if (result.savedAt == null) return null

        return MobileSessionState(
            damagePoints = result.damagePoints.map { point ->
                MobileSessionPoint(
                    id = point.id,
                    x = point.x,
                    y = point.y,
                    note = point.note,
                    photos = point.photos.map { photo ->
                        MobileSessionPhoto(
                            photoId = photo.photoId,
                            thumbnailUrl = photo.s3Key?.let { key ->
                                runCatching { checkinPhotoService.generateDownloadUrl(key) }.getOrNull()
                            },
                            strokes = photo.strokes
                        )
                    }
                )
            },
            vehicleType = result.vehicleType,
            savedAt = result.savedAt
        )
    }

    /**
     * Przenosi zdjęcia zrobione telefonem do galerii wizyty i zwraca mapowanie
     * „identyfikator tymczasowy → zdjęcie wizyty".
     *
     * Dlaczego osobnym krokiem, a nie przy zapisie mapy: punkt uszkodzenia wskazuje
     * zdjęcie po identyfikatorze, a ten zmienia się w momencie przeniesienia. Gdyby
     * przeniesienie działo się dopiero przy „Zapisz", okno przez cały czas edycji
     * trzymałoby wskaźniki na pliki tymczasowe, których obiekt S3 zaraz przestaje
     * istnieć — i wystarczyłby jeden nieudany zapis, żeby zdjęcia zniknęły razem z
     * sesją. Tu zdjęcie staje się zdjęciem wizyty od razu po zrobieniu.
     *
     * Skutek uboczny, świadomy: zdjęcie zostaje w galerii wizyty także wtedy, gdy
     * operator porzuci okno bez zapisu. Zdjęcie samochodu w kartotece nikomu nie
     * szkodzi, a alternatywy — osierocone obiekty w S3 albo kasowanie cudzej pracy —
     * są wyraźnie gorsze.
     */
    @Transactional
    suspend fun claimPhotos(
        visitId: VisitId,
        studioId: StudioId,
        userId: UserId,
        userName: String
    ): List<ClaimedMobilePhoto> {
        val visitEntity = requireOpenVisit(visitId, studioId)
        val tenantId = studioId.value.toString()
        val checkinId = visitId.value.toString()

        val finalized = checkinPhotoService.finalizePhotos(
            tenantId = tenantId,
            checkinId = checkinId,
            visitId = visitId
        )
        if (finalized.isEmpty()) return emptyList()

        val now = Instant.now()
        /*
         * Wiersze wstawiamy przez repozytorium zdjęć, a NIE przez
         * `visitEntity.photos` + `visitRepository.save(...)`. Kolekcja ma
         * `orphanRemoval = true`, więc przepisanie jej na częściowo doczytanym
         * agregacie usuwa zdjęcia, których w niej nie było. Tu dokładamy wiersze i
         * nic więcej nie może się stać.
         */
        val rows = finalized.map { photo ->
            VisitPhotoEntity(
                id = photo.photoId,
                visit = visitEntity,
                fileId = photo.fileId,
                fileName = photo.fileName,
                description = null,
                uploadedAt = now,
                uploadedBy = userId.value,
                uploadedByName = userName
            )
        }
        visitPhotoRepository.saveAll(rows)

        logger.info("Claimed ${rows.size} mobile damage photo(s) into visit={}", visitId)

        return finalized.map { photo ->
            ClaimedMobilePhoto(
                // Nazwa pliku w magazynie tymczasowym to „{photoId}.{ext}", a ten
                // photoId jest tym, którym punkty uszkodzeń wskazują zdjęcie.
                temporaryPhotoId = photo.fileName.substringBeforeLast('.'),
                photoId = photo.photoId.toString(),
                fileName = photo.fileName,
                thumbnailUrl = runCatching { photoSessionService.generateDownloadUrl(photo.fileId) }.getOrNull()
            )
        }
    }

    private fun requireOpenVisit(visitId: VisitId, studioId: StudioId) =
        visitRepository.findByIdAndStudioId(visitId.value, studioId.value)
            ?.also { visit ->
                if (visit.status in CLOSED_STATUSES) {
                    throw ValidationException(
                        "Mapy uszkodzeń nie można już zmienić — wizyta jest zamknięta."
                    )
                }
            }
            ?: throw EntityNotFoundException("Visit not found: $visitId")

    /**
     * Punkty NIE tracą przypisanych zdjęć po drodze na telefon: telefon odsyła pełną
     * mapę, a okno zastępuje nią swoją listę. Gdyby zdjęcia tu wypadły, pierwszy
     * zapis z telefonu zrzucałby je z punktów — i to bez żadnego komunikatu.
     */
    private fun toSessionData(point: DamagePoint, visitPhotoKeys: Map<String, String>) = DamagePointData(
        id = point.id,
        x = point.x,
        y = point.y,
        note = point.note,
        photos = point.photos.map { photo ->
            DamagePointPhotoData(
                photoId = photo.photoId,
                // null tylko wtedy, gdy wskazanego zdjęcia nie ma w galerii wizyty;
                // punkt zostaje, kafelek na telefonie będzie bez obrazka.
                s3Key = visitPhotoKeys[photo.photoId],
                strokes = photo.strokes.map { stroke ->
                    AnnotationStrokeData(
                        color = stroke.color,
                        width = stroke.width,
                        points = stroke.points.map { AnnotationPointData(x = it.x, y = it.y) }
                    )
                }
            )
        }
    )
}

data class MobileSessionToken(
    val token: String,
    val checkinId: String,
    val expiresAt: Instant
)

data class MobileSessionState(
    val damagePoints: List<MobileSessionPoint>,
    val vehicleType: String?,
    val savedAt: Instant
)

data class MobileSessionPoint(
    val id: Int,
    val x: Double,
    val y: Double,
    val note: String?,
    val photos: List<MobileSessionPhoto>
)

data class MobileSessionPhoto(
    val photoId: String,
    val thumbnailUrl: String?,
    val strokes: List<AnnotationStrokeData>
)

data class ClaimedMobilePhoto(
    /** Identyfikator, którym punkty wskazywały zdjęcie w sesji mobilnej. */
    val temporaryPhotoId: String,
    /** Identyfikator wiersza zdjęcia wizyty, którym mają wskazywać od teraz. */
    val photoId: String,
    val fileName: String,
    val thumbnailUrl: String?
)
