package pl.detailing.crm.visit.damagemap

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.checkin.qr.AnnotationPointData
import pl.detailing.crm.checkin.qr.AnnotationStrokeData
import pl.detailing.crm.checkin.qr.CheckinDamagePointsService
import pl.detailing.crm.checkin.qr.CheckinPhotoService
import pl.detailing.crm.checkin.qr.DamagePointData
import pl.detailing.crm.checkin.qr.DamagePointPhotoData
import pl.detailing.crm.checkin.qr.UploadContextTokenService
import pl.detailing.crm.checkin.qr.UploadSessionPurpose
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
import java.time.Duration
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
    private val photoSessionService: PhotoSessionService,
    private val redisTemplate: StringRedisTemplate
) {
    companion object {
        private val logger = LoggerFactory.getLogger(VisitDamageMapMobileService::class.java)

        /** Te same statusy, których nie wpuszcza [UpdateVisitDamageMapHandler]. */
        private val CLOSED_STATUSES = setOf(VisitStatus.COMPLETED, VisitStatus.REJECTED, VisitStatus.ARCHIVED)

        private const val PHOTO_MAP_KEY_PREFIX = "visit-damage-map:photo-map:"

        /**
         * Mapowanie „zdjęcie tymczasowe → zdjęcie wizyty" żyje DŁUŻEJ niż sesja QR
         * (3 h). Telefon trzyma własny stan i po zamknięciu sesji nadal potrafi
         * przysłać stary identyfikator; bez mapowania punkt straciłby zdjęcie.
         */
        private val PHOTO_MAP_TTL = Duration.ofHours(48)
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
            rotate = rotate,
            // Telefon ma pokazać wyłącznie uszkodzenia: przy otwartej wizycie zdjęcie
            // bez przypisania do punktu nie jest tym, po co ktoś skanuje ten kod.
            purpose = UploadSessionPurpose.DAMAGE_MAP
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
     * Uzgadnia stan sesji mobilnej z wizytą i zwraca punkty GOTOWE do wstawienia w
     * edytor: z identyfikatorami zdjęć WIZYTY i podpisanymi miniaturami.
     *
     * Jedno wywołanie robi wszystko, bo rozbicie tego na „przenieś zdjęcia" +
     * „przetłumacz u siebie" było źródłem obu zgłoszonych błędów. Telefon przy
     * dodaniu zdjęcia wysyła DWA zdarzenia (wysłano zdjęcie, zapisano punkty), okno
     * odpalało na każde osobne przeniesienie, a te biegły równolegle:
     *  - `finalizePhotos` kopiował ten sam obiekt dwa razy i powstawały DWA wiersze
     *    zdjęcia wizyty — zdjęcie pokazywało się podwójnie na liście „Istniejące",
     *  - tłumaczenie po stronie okna czytało tablicę mapowań, zanim którekolwiek
     *    przeniesienie ją wypełniło, więc zdjęcie wypadało z punktu.
     *
     * Teraz tłumaczy serwer, z mapowania trzymanego w Redisie. Jest to więc
     * operacja IDEMPOTENTNA: zdjęcie raz przeniesione ma swój wpis i drugie
     * wywołanie już go nie dubluje.
     *
     * Zwraca null, gdy nie ma sesji ani zapisanych punktów.
     */
    @Transactional
    suspend fun syncSession(
        visitId: VisitId,
        studioId: StudioId,
        userId: UserId,
        userName: String
    ): MobileSessionState? {
        val visitEntity = requireOpenVisit(visitId, studioId)
        val tenantId = studioId.value.toString()
        val checkinId = visitId.value.toString()

        // 1. Przenieś nowe zdjęcia z telefonu do galerii wizyty (pomija już przeniesione).
        claimPendingPhotos(visitEntity, visitId, studioId, userId, userName)

        val stored = checkinDamagePointsService.getDamagePoints(tenantId, checkinId)
        if (stored.savedAt == null) return null

        // 2. Przetłumacz identyfikatory i dołóż adresy miniatur ze zdjęć wizyty.
        val photoMap = readPhotoMap(tenantId, checkinId)
        val visitPhotoKeys = visitRepository
            .findByIdAndStudioIdWithPhotos(visitId.value, studioId.value)
            ?.photos
            ?.associate { it.id.toString() to it.fileId }
            .orEmpty()

        return MobileSessionState(
            damagePoints = stored.damagePoints.map { point ->
                MobileSessionPoint(
                    id = point.id,
                    x = point.x,
                    y = point.y,
                    note = point.note,
                    photos = point.photos.mapNotNull { photo ->
                        /*
                         * Trzy możliwe postaci identyfikatora:
                         *  - zdjęcie tymczasowe przeniesione do wizyty → z mapowania,
                         *  - zdjęcie wizyty (punkt zasiany z komputera) → bez zmian,
                         *  - `local-…`, czyli placeholder telefonu przed zakończeniem
                         *    wysyłki → pomijamy; kolejne zdarzenie przyniesie je już
                         *    z prawdziwym identyfikatorem.
                         */
                        val resolved = photoMap[photo.photoId]
                            ?: photo.photoId.takeIf { visitPhotoKeys.containsKey(it) }
                            ?: return@mapNotNull null

                        MobileSessionPhoto(
                            photoId = resolved,
                            thumbnailUrl = visitPhotoKeys[resolved]?.let { key ->
                                runCatching { photoSessionService.generateDownloadUrl(key) }.getOrNull()
                            },
                            strokes = photo.strokes
                        )
                    }
                )
            },
            vehicleType = stored.vehicleType,
            savedAt = stored.savedAt
        )
    }

    /**
     * Przenosi zdjęcia leżące w magazynie tymczasowym sesji do galerii wizyty i
     * zapisuje mapowanie identyfikatorów.
     *
     * Dlaczego zdjęcie staje się zdjęciem wizyty od razu, a nie przy zapisie mapy:
     * punkt wskazuje zdjęcie po identyfikatorze, a ten zmienia się przy przeniesieniu.
     * Gdyby przeniesienie czekało na „Zapisz", okno przez całą edycję trzymałoby
     * wskaźniki na pliki, których obiekt S3 zaraz przestaje istnieć.
     *
     * Skutek uboczny, świadomy: zdjęcie zostaje w galerii wizyty także wtedy, gdy
     * operator porzuci okno bez zapisu. Zdjęcie samochodu w kartotece nikomu nie
     * szkodzi, a osierocone obiekty w S3 albo kasowanie cudzej pracy szkodzą.
     */
    private suspend fun claimPendingPhotos(
        visitEntity: pl.detailing.crm.visit.infrastructure.VisitEntity,
        visitId: VisitId,
        studioId: StudioId,
        userId: UserId,
        userName: String
    ) {
        val tenantId = studioId.value.toString()
        val checkinId = visitId.value.toString()

        val finalized = checkinPhotoService.finalizePhotos(
            tenantId = tenantId,
            checkinId = checkinId,
            visitId = visitId
        )
        if (finalized.isEmpty()) return

        val mapKey = PHOTO_MAP_KEY_PREFIX + tenantId + ":" + checkinId
        val alreadyMapped = readPhotoMap(tenantId, checkinId)
        val now = Instant.now()

        val rows = mutableListOf<VisitPhotoEntity>()
        val newMappings = mutableMapOf<String, String>()

        finalized.forEach { photo ->
            // Nazwa pliku w magazynie tymczasowym to „{photoId}.{ext}" — i to jest
            // ten identyfikator, którym punkty uszkodzeń wskazują zdjęcie.
            val temporaryId = photo.fileName.substringBeforeLast('.')
            if (alreadyMapped.containsKey(temporaryId)) {
                logger.warn(
                    "Zdjęcie {} było już przeniesione — pomijam drugi wiersz [visit={}]",
                    temporaryId, visitId
                )
                return@forEach
            }
            newMappings[temporaryId] = photo.photoId.toString()
            rows += VisitPhotoEntity(
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

        if (rows.isEmpty()) return

        /*
         * Wiersze wstawiamy przez repozytorium zdjęć, a NIE przez
         * `visitEntity.photos` + `visitRepository.save(...)`. Kolekcja ma
         * `orphanRemoval = true`, więc przepisanie jej na częściowo doczytanym
         * agregacie usuwa zdjęcia, których w niej nie było.
         */
        visitPhotoRepository.saveAll(rows)
        redisTemplate.opsForHash<String, String>().putAll(mapKey, newMappings)
        redisTemplate.expire(mapKey, PHOTO_MAP_TTL)

        logger.info("Przeniesiono {} zdjęcie/a z telefonu do wizyty {}", rows.size, visitId)
    }

    private fun readPhotoMap(tenantId: String, checkinId: String): Map<String, String> =
        try {
            redisTemplate.opsForHash<String, String>()
                .entries(PHOTO_MAP_KEY_PREFIX + tenantId + ":" + checkinId)
        } catch (e: Exception) {
            logger.warn("Nie udało się odczytać mapowania zdjęć sesji mobilnej: ${e.message}")
            emptyMap()
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
