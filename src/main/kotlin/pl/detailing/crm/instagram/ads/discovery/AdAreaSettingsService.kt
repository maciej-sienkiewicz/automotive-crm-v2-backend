package pl.detailing.crm.instagram.ads.discovery

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.LocalDate

/**
 * Ustawienia rejonu jednego studia — odczyt i zapis, nic więcej.
 *
 * Jeden wiersz na studio zamiast listy nazwanych śledzeń. Powód jest w encji:
 * po przejściu na wspólny katalog fraz wszystkie śledzenia jednego studia miały
 * identyczne frazy i różniły się wyłącznie miejscowościami.
 *
 * Celowo bez ruchu do Meta: zapis to sam zapis. Frazy trafiają do biblioteki
 * dopiero przy odczycie wyników ([AdDiscoveryReadService]) i przy cyklicznym
 * odświeżaniu ([AdDiscoveryScheduler]) — dzięki temu ta transakcja nigdy nie
 * obejmuje wywołania HTTP.
 */
@Service
class AdAreaSettingsService(
    private val settingsRepository: AdAreaSettingsRepository,
    @Value("\${meta.ads.discovery.max-locations:20}") private val maxLocations: Int
) {

    /**
     * Ustawienia studia albo stan pusty.
     *
     * Brak wiersza nie jest błędem — to studio, które jeszcze nie wskazało rejonu.
     * Oddajemy wtedy puste ustawienia zamiast 404, bo ekran ma pokazać formularz,
     * a nie komunikat o braku czegoś, czego nikt jeszcze nie zakładał.
     */
    @Transactional(readOnly = true)
    fun get(studioId: StudioId): AreaSettingsDto =
        settingsRepository.findById(studioId.value).orElse(null)?.toDto()
            ?: AreaSettingsDto(
                locations = emptyList(),
                matchMode = AreaMatchMode.INCLUDE_BROADER,
                excludedPhraseIds = emptyList(),
                trackedPhraseCount = AdDiscoveryCatalog.ALL.size,
                noveltyAckedThrough = null,
                updatedAt = null
            )

    @Transactional
    fun save(studioId: StudioId, userId: UserId, request: SaveAreaSettingsRequest): AreaSettingsDto {
        val locations = cleanLocations(request.locations)
        val excluded = cleanExcluded(request.excludedPhraseIds)

        val entity = settingsRepository.findById(studioId.value).orElse(null)
            ?: AdAreaSettingsEntity(studioId = studioId.value)

        entity.locations = AreaLists.encode(locations)
        entity.excludedPhraseIds = AreaLists.encode(excluded)
        entity.matchMode = request.matchMode ?: entity.matchMode
        entity.updatedByUserId = userId.value
        entity.updatedAt = Instant.now()

        return settingsRepository.save(entity).toDto()
    }

    /**
     * „Odznacz nowe": studio potwierdza, że widziało wszystko, co ruszyło do dziś.
     *
     * Zapis jest IDEMPOTENTNY i nieodwracalny w jedną stronę — data może iść tylko
     * do przodu. Dwa kliknięcia tego samego dnia to jeden stan, a przypadkowe
     * cofnięcie (np. zapis starszą datą z innego wątku) nie przywróci pigułek,
     * które ktoś już odznaczył.
     *
     * Brak wiersza ustawień nie jest błędem: studio, które nie wskazało rejonu,
     * nie ma czego odznaczać, więc oddajemy stan pusty zamiast zakładać wiersz
     * bez ani jednej miejscowości (encja wymaga rejonu, żeby cokolwiek znaczyć).
     */
    @Transactional
    fun acknowledgeNovelty(studioId: StudioId, userId: UserId, today: LocalDate = AreaNovelty.today()): LocalDate? {
        val entity = settingsRepository.findById(studioId.value).orElse(null) ?: return null

        val current = entity.noveltyAckedThrough
        if (current == null || current.isBefore(today)) {
            entity.noveltyAckedThrough = today
            entity.updatedByUserId = userId.value
            entity.updatedAt = Instant.now()
            settingsRepository.save(entity)
        }
        return entity.noveltyAckedThrough
    }

    private fun cleanLocations(raw: List<String>): List<String> {
        val locations = raw.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (locations.isEmpty()) {
            throw ValidationException("Podaj co najmniej jedną miejscowość rejonu.")
        }
        if (locations.size > maxLocations) {
            throw ValidationException("Maksymalnie $maxLocations miejscowości w rejonie.")
        }
        return locations
    }

    /**
     * Odznaczone frazy sprowadzone do identyfikatorów, które KATALOG faktycznie zna.
     *
     * Nieznane identyfikatory milcząco odpadają zamiast wywracać zapis: po usunięciu
     * frazy z katalogu czyjeś stare wykluczenie wskazuje na nic, a to nie jest błąd
     * użytkownika ani powód, żeby nie dało mu się zapisać rejonu.
     *
     * Odznaczenie WSZYSTKIEGO jest niedozwolone — to ustawienie, które nigdy niczego
     * nie pokaże, a w koszcie odświeżania wygląda jak każde inne.
     */
    private fun cleanExcluded(raw: List<String>): List<String> {
        val excluded = raw.map { it.trim() }.filter(AdDiscoveryCatalog::exists).distinct()
        if (excluded.size >= AdDiscoveryCatalog.ALL.size) {
            throw ValidationException("Zostaw zaznaczoną przynajmniej jedną frazę — inaczej nie ma czego śledzić.")
        }
        return excluded
    }

    private fun AdAreaSettingsEntity.toDto(): AreaSettingsDto {
        val excluded = AreaLists.decode(excludedPhraseIds)
        return AreaSettingsDto(
            locations = AreaLists.decode(locations),
            matchMode = matchMode,
            excludedPhraseIds = excluded,
            trackedPhraseCount = AdDiscoveryCatalog.ALL.size - excluded.count(AdDiscoveryCatalog::exists),
            noveltyAckedThrough = noveltyAckedThrough?.toString(),
            updatedAt = updatedAt.toString()
        )
    }
}

/** Listy krótkich wartości (miejscowości, identyfikatory fraz) jako tekst rozdzielony `|`. */
object AreaLists {
    private const val SEP = "|"

    fun encode(values: List<String>): String =
        values.joinToString(SEP) { it.replace(SEP, " ").trim() }

    fun decode(raw: String): List<String> =
        raw.split(SEP).map { it.trim() }.filter { it.isNotBlank() }
}
