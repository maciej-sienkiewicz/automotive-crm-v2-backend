package pl.detailing.crm.instagram.ads.discovery

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Śledzenia obszaru jednego studia — CRUD i nic więcej.
 *
 * Celowo bez ruchu do Meta: zapis to sam zapis. Frazy trafiają do biblioteki
 * dopiero przy odczycie wyników (on-demand w [AdDiscoveryReadService]) i przy
 * cyklicznym odświeżaniu ([AdDiscoveryScheduler]) — dzięki temu ta transakcja
 * nigdy nie obejmuje wywołania HTTP.
 *
 * Wszystko jest twardo zawężone do `studioId`: aplikacja jest wielonajemcowa, a
 * śledzenie należy do jednego studia. Odczyt i kasowanie idą przez zapytania z
 * `studio_id` w warunku, nie przez samo id — żeby cudze id nie otwierało furtki.
 */
@Service
class AdLocationTrackingService(
    private val trackingRepository: AdLocationTrackingRepository,
    @Value("\${meta.ads.discovery.max-trackings-per-studio:25}") private val maxTrackingsPerStudio: Int,
    @Value("\${meta.ads.discovery.max-locations-per-tracking:20}") private val maxLocationsPerTracking: Int
) {

    @Transactional(readOnly = true)
    fun list(studioId: StudioId): List<LocationTrackingDto> =
        trackingRepository.findByStudioIdOrderByCreatedAtDesc(studioId.value).map { it.toDto() }

    @Transactional(readOnly = true)
    fun get(studioId: StudioId, id: UUID): LocationTrackingDto? =
        trackingRepository.findByIdAndStudioId(id, studioId.value)?.toDto()

    @Transactional
    fun create(studioId: StudioId, userId: UserId, request: SaveLocationTrackingRequest): LocationTrackingDto {
        val label = cleanLabel(request.label)
        val excluded = cleanExcluded(request.excludedPhraseIds)
        val locations = cleanLocations(request.locations)

        if (trackingRepository.countByStudioId(studioId.value) >= maxTrackingsPerStudio) {
            throw ConflictException(
                "Osiągnięto limit $maxTrackingsPerStudio śledzeń obszaru. Usuń nieużywane, aby dodać nowe."
            )
        }

        val now = Instant.now()
        val entity = trackingRepository.save(
            AdLocationTrackingEntity(
                id = UUID.randomUUID(),
                studioId = studioId.value,
                label = label,
                excludedPhraseIds = TrackingLists.encode(excluded),
                locations = TrackingLists.encode(locations),
                matchMode = request.matchMode ?: AreaMatchMode.INCLUDE_BROADER,
                active = request.active ?: true,
                createdByUserId = userId.value,
                createdAt = now,
                updatedAt = now
            )
        )
        return entity.toDto()
    }

    @Transactional
    fun update(studioId: StudioId, id: UUID, request: SaveLocationTrackingRequest): LocationTrackingDto? {
        val entity = trackingRepository.findByIdAndStudioId(id, studioId.value) ?: return null

        entity.label = cleanLabel(request.label)
        entity.excludedPhraseIds = TrackingLists.encode(cleanExcluded(request.excludedPhraseIds))
        entity.locations = TrackingLists.encode(cleanLocations(request.locations))
        request.matchMode?.let { entity.matchMode = it }
        request.active?.let { entity.active = it }
        entity.updatedAt = Instant.now()

        return trackingRepository.save(entity).toDto()
    }

    @Transactional
    fun delete(studioId: StudioId, id: UUID): Boolean =
        trackingRepository.deleteByIdAndStudioId(id, studioId.value) > 0

    // ── Walidacja i czyszczenie wejścia ──────────────────────────────────────

    private fun cleanLabel(raw: String): String =
        raw.trim().take(120).takeIf { it.isNotBlank() }
            ?: throw ValidationException("Nazwa śledzenia jest wymagana.")

    /**
     * Odznaczone frazy sprowadzone do identyfikatorów, które KATALOG faktycznie zna.
     *
     * Nieznane identyfikatory milcząco odpadają zamiast wywracać zapis: po usunięciu
     * frazy z katalogu czyjeś stare wykluczenie wskazuje na nic, a to nie jest błąd
     * użytkownika ani powód, żeby nie dało mu się zapisać rejonu.
     *
     * Odznaczenie WSZYSTKIEGO jest niedozwolone — to śledzenie, które nigdy niczego
     * nie pokaże, a w koszcie odświeżania wygląda jak każde inne.
     */
    private fun cleanExcluded(raw: List<String>): List<String> {
        val excluded = raw.map { it.trim() }.filter(AdDiscoveryCatalog::exists).distinct()
        if (excluded.size >= AdDiscoveryCatalog.ALL.size) {
            throw ValidationException("Zostaw zaznaczoną przynajmniej jedną frazę — inaczej nie ma czego śledzić.")
        }
        return excluded
    }

    private fun cleanLocations(raw: List<String>): List<String> {
        val locations = raw.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (locations.isEmpty()) {
            throw ValidationException("Podaj co najmniej jedną miejscowość rejonu.")
        }
        if (locations.size > maxLocationsPerTracking) {
            throw ValidationException("Maksymalnie $maxLocationsPerTracking miejscowości na jedno śledzenie.")
        }
        return locations
    }

    private fun AdLocationTrackingEntity.toDto() = LocationTrackingDto(
        id = id.toString(),
        label = label,
        excludedPhraseIds = TrackingLists.decode(excludedPhraseIds),
        trackedPhraseCount = AdDiscoveryCatalog.ALL.size - TrackingLists.decode(excludedPhraseIds).size,
        locations = TrackingLists.decode(locations),
        matchMode = matchMode,
        active = active,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString()
    )
}

/** Listy krótkich wartości (frazy, miejscowości) jako tekst rozdzielony `|`. */
object TrackingLists {
    private const val SEP = "|"

    fun encode(values: List<String>): String =
        values.joinToString(SEP) { it.replace(SEP, " ").trim() }

    fun decode(raw: String): List<String> =
        raw.split(SEP).map { it.trim() }.filter { it.isNotBlank() }
}
