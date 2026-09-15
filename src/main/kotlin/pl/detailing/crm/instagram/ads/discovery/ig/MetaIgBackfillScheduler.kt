package pl.detailing.crm.instagram.ads.discovery.ig

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.detailing.crm.instagram.ads.discovery.AdDiscoveryAdRepository

/**
 * Uzupełnianie nazw profili IG w tle, po kilka stron na raz.
 *
 * DLACZEGO W TLE, A NIE PRZY ODCZYCIE. Jedno sprawdzenie to uruchomienie
 * Chromium, wejście na stronę reklamodawcy i dwa kliknięcia — kilkanaście
 * sekund. Tabela „Reklamodawcy w okolicy" ma się otworzyć natychmiast, więc
 * czyta wyłącznie to, co już zapisane, a brakujące nazwy dolatują później.
 *
 * DLACZEGO PO KILKA. Uchwyt profilu nie zmienia się nigdy, więc po pierwszym
 * przejściu przez listę reklamodawców praca się kończy sama. Kilkanaście firm
 * zostanie ustalonych w godzinę, a potem ten harmonogram nie robi nic tygodniami
 * — dokładnie tak ma być. To nie jest zbieranie danych, tylko jednorazowe
 * uzupełnienie słownika.
 */
@Component
class MetaIgBackfillScheduler(
    private val adRepository: AdDiscoveryAdRepository,
    private val lookupService: MetaIgLookupService,
    private val resolver: MetaIgResolverClient,
    @Value("\${meta.ads.ig-resolver.per-tick:2}") private val perTick: Int,
    @Value("\${meta.ads.ig-resolver.candidate-window:200}") private val candidateWindow: Int
) {
    private val log = LoggerFactory.getLogger(MetaIgBackfillScheduler::class.java)

    @Scheduled(cron = "\${meta.ads.ig-resolver.backfill-cron:0 */7 * * * *}")
    fun tick() {
        if (!resolver.enabled) return

        val candidates = runCatching {
            adRepository.distinctPageIds(PageRequest.of(0, candidateWindow))
        }
            .getOrElse {
                log.warn("Nie udało się pobrać stron do uzupełnienia profili IG: {}", it.message)
                return
            }

        val todo = lookupService.needingLookup(candidates, perTick)
        if (todo.isEmpty()) return

        todo.forEach { pageId ->
            // Każda strona we własnej transakcji i we własnym `runCatching` —
            // jedna niepowodzona nie ma zatrzymywać pozostałych ani harmonogramu.
            runCatching { lookupService.lookupAndStore(pageId) }
                .onFailure { log.warn("Uzupełnianie profilu IG strony {} przerwane: {}", pageId, it.message) }
        }
    }
}
