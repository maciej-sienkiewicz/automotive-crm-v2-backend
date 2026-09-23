package pl.detailing.crm.instagram.ads.discovery

import pl.detailing.crm.rolepreview.RolePreviewOutboundGuard
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Odświeżanie wspólnego cache odkrywania — **strumieniem przez całą dobę**, nie zrywami.
 *
 * Limit Meta jest GODZINOWY (180 wywołań/godz. na całą instalację), a nie dobowy.
 * Dwa duże przebiegi o 10:00 i 18:00 były więc najgorszym możliwym kształtem: każdy
 * próbował zmieścić cały katalog w jednej godzinie, wyczerpywał limit w kilka minut,
 * zabierał go nocnemu syncowi i interaktywnym zapytaniom, a i tak nie kończył listy.
 *
 * Teraz budzimy się co kilka minut i bierzemy garść najbardziej zwietrzałych fraz.
 * Ten sam katalog odświeża się w całości, tylko rozłożony równo — a limit godzinowy
 * przestaje być klifem i staje się czymś, czego nigdy nie dotykamy.
 *
 * Arytmetyka przy ustawieniach domyślnych: co 10 minut jedna fraza po najwyżej
 * 8 stron to ~48 wywołań na godzinę. Zostaje ponad 70% budżetu na nocny sync
 * obserwowanych profili i na to, co ktoś akurat kliknie na ekranie.
 *
 * [minRefreshInterval] jest drugą połową tego pomysłu: gdy fraz w użyciu jest mało,
 * tykanie co 10 minut zamieniłoby się w odpytywanie tej samej frazy bez końca.
 * Frazę świeższą niż ten próg pomijamy, a gdy nie ma czego brać — przebieg jest
 * po prostu pusty i cichy.
 */
@Component
class AdDiscoveryScheduler(
    private val settingsRepository: AdAreaSettingsRepository,
    private val phraseRepository: AdDiscoveryPhraseRepository,
    private val fetchService: AdDiscoveryFetchService,
    @Value("\${meta.ads.discovery.enabled:true}") private val enabled: Boolean,
    @Value("\${meta.ads.discovery.phrases-per-tick:1}") private val phrasesPerTick: Int,
    @Value("\${meta.ads.discovery.min-refresh-hours:6}") minRefreshHours: Long,
    private val rolePreviewGuard: RolePreviewOutboundGuard
) {
    private val log = LoggerFactory.getLogger(AdDiscoveryScheduler::class.java)

    /** Nie ruszamy frazy, która i tak jest świeższa niż ten próg. */
    private val minRefreshInterval: Duration = Duration.ofHours(minRefreshHours)

    @Scheduled(cron = "\${meta.ads.discovery.cron:0 */10 * * * *}")
    fun refresh() {
        if (!enabled) return

        try {
            val due = stalestDue()
            if (due.isEmpty()) return

            for (phrase in due) {
                val status = fetchService.fetchPhrase(phrase)
                if (status == PhraseFetchStatus.RATE_LIMITED) {
                    // Przy strumieniu to nie powinno się zdarzać — jeśli się zdarza,
                    // znaczy że budżet zjada coś innego. Kończymy tykanie bez hałasu:
                    // za kilka minut spróbujemy znowu, a fraza zostaje najstarsza,
                    // więc wróci na początek kolejki sama.
                    log.info("Odkrywanie reklam: limit wywołań zajęty, fraza „{}” poczeka na kolejne tyknięcie", phrase)
                    return
                }
            }
            log.debug("Odkrywanie reklam: odświeżono {} fraz(y)", due.size)
        } catch (e: Exception) {
            log.error("Odkrywanie reklam: nieoczekiwany błąd odświeżania: {}", e.message, e)
        }
    }

    /**
     * Najbardziej zwietrzałe frazy w użyciu, najwyżej [phrasesPerTick] sztuk.
     *
     * „W użyciu" to katalog pomniejszony o odznaczenia każdego aktywnego śledzenia:
     * frazy, których nie śledzi nikt, nie kosztują ani jednego wywołania.
     *
     * Fraza nigdy niepobrana nie ma wpisu w tabeli fraz — idzie na sam początek,
     * bo jest starsza niż cokolwiek pobranego.
     */
    private fun stalestDue(): List<String> {
        val inUse = settingsRepository.findAllConfigured()
            // Ustawienia piaskownicy podglądu roli nie zamawiają pobrań z Meta.
            .filterNot { rolePreviewGuard.isSandbox(it.studioId) }
            .flatMap { AdDiscoveryCatalog.phrasesExcept(AreaLists.decode(it.excludedPhraseIds)) }
            .mapNotNull(AdDiscoveryPhrase::normalizeValid)
            .distinct()
        if (inUse.isEmpty()) return emptyList()

        val fetchedAt = phraseRepository.findByPhraseIn(inUse).associate { it.phrase to it.lastFetchedAt }
        val cutoff = Instant.now().minus(minRefreshInterval)

        return inUse
            .filter { phrase -> fetchedAt[phrase]?.isAfter(cutoff) != true }
            .sortedWith(compareBy(nullsFirst()) { fetchedAt[it] })
            .take(phrasesPerTick.coerceAtLeast(1))
    }
}
