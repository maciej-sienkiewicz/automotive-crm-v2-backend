package pl.detailing.crm.instagram.ads.discovery

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Cykliczne odświeżanie wspólnego cache odkrywania — dwa razy na dobę, 10:00 i 18:00.
 *
 * Odświeżamy WYŁĄCZNIE frazy z aktywnych śledzeń obszaru (dowolnego studia), zebrane
 * i odduplikowane. Fraza, którą ktoś podejrzał raz i nie zapisał, nie obciąża tego
 * przebiegu — wygasa z cache po TTL. To trzyma liczbę wywołań Meta w ryzach: rośnie
 * z liczbą UNIKALNYCH śledzonych fraz, nie z liczbą studiów ani odsłon ekranu.
 *
 * Dwa razy dziennie, bo reklamy trwają tygodniami — częściej nie przyniosłoby
 * nowych danych, a zjadałoby wspólny limit tokena. Ten sam token obsługuje nocny
 * sync obserwowanych profili (5:45), więc rozkładamy się w czasie i nie kolidujemy.
 */
@Component
class AdDiscoveryScheduler(
    private val trackingRepository: AdLocationTrackingRepository,
    private val phraseRepository: AdDiscoveryPhraseRepository,
    private val fetchService: AdDiscoveryFetchService,
    @Value("\${meta.ads.discovery.enabled:true}") private val enabled: Boolean
) {
    private val log = LoggerFactory.getLogger(AdDiscoveryScheduler::class.java)

    @Scheduled(cron = "\${meta.ads.discovery.cron:0 0 10,18 * * *}")
    fun refresh() {
        if (!enabled) return

        try {
            val phrases = phrasesInUse()

            if (phrases.isEmpty()) {
                log.debug("Odkrywanie reklam: brak aktywnych śledzeń — nic do odświeżenia")
                return
            }

            log.info("Odkrywanie reklam: odświeżam {} fraz w użyciu, od najdawniej pobranej", phrases.size)
            var refreshed = 0
            for (phrase in phrases) {
                val status = fetchService.fetchPhrase(phrase)
                if (status == PhraseFetchStatus.RATE_LIMITED) {
                    // Wspólny limit tokena wyczerpany — reszta poczeka na kolejny przebieg,
                    // tak samo jak nocny sync przerywa się i dokańcza nazajutrz.
                    log.warn("Odkrywanie reklam: limit wywołań wyczerpany po {} frazach — przerwane", refreshed)
                    break
                }
                refreshed++
            }
            log.info("Odkrywanie reklam: odświeżono {}/{} fraz", refreshed, phrases.size)
        } catch (e: Exception) {
            log.error("Odkrywanie reklam: nieoczekiwany błąd odświeżania: {}", e.message, e)
        }
    }

    /**
     * Frazy do odświeżenia, w kolejności OD NAJDAWNIEJ POBRANEJ.
     *
     * Kolejność jest tu funkcją, nie kosmetyką. Katalog ma kilkadziesiąt fraz, a
     * każda potrafi zająć kilka stron z limitu 180 wywołań/godz. na całą instalację;
     * przy stałej kolejności koniec listy nie odświeżyłby się nigdy, bo limit
     * wyczerpywałby się zawsze na tych samych pozycjach. Sortowanie po
     * `lastFetchedAt` sprawia, że każdy przebieg bierze to, co najbardziej zwietrzało,
     * a przez kilka przebiegów katalog nadrabia się w całości.
     *
     * Fraza nigdy niepobrana nie ma wpisu w tabeli fraz — i właśnie dlatego idzie
     * na sam początek: jest starsza niż cokolwiek pobranego.
     */
    private fun phrasesInUse(): List<String> {
        val inUse = trackingRepository.findByActiveTrue()
            .flatMap { AdDiscoveryCatalog.phrasesExcept(TrackingLists.decode(it.excludedPhraseIds)) }
            .mapNotNull(AdDiscoveryPhrase::normalizeValid)
            .distinct()
        if (inUse.isEmpty()) return emptyList()

        val fetchedAt = phraseRepository.findByPhraseIn(inUse).associate { it.phrase to it.lastFetchedAt }
        return inUse.sortedWith(compareBy(nullsFirst()) { fetchedAt[it] })
    }
}
