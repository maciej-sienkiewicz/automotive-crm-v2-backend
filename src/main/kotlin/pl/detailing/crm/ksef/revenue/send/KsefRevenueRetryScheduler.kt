package pl.detailing.crm.ksef.revenue.send

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import java.time.Duration
import java.time.Instant

/**
 * Domyka cykl życia faktur przychodowych, których wysyłka nie zakończyła się
 * synchronicznie:
 *
 * - QUEUED_RETRY  – niedostępność KSeF w chwili wystawienia (tryb offline24);
 *                   dosyłamy automatycznie. Ustawa wymaga dosłania najpóźniej
 *                   następnego dnia roboczego — po przekroczeniu progu ostrzegawczego
 *                   logujemy błąd (widoczny w monitoringu), ale nie przestajemy próbować.
 * - SENDING       – stan przejściowy osierocony przez restart aplikacji w trakcie
 *                   wysyłki; po przekroczeniu progu wraca do kolejki retry
 *                   (idempotencja: wysyłka mogła nie dojść do KSeF — jeżeli doszła,
 *                   duplikat zostanie odrzucony przez KSeF po hashu/numerze własnym).
 * - SUBMITTED     – przyjęta do sesji, brak finalnego statusu; dokańczamy polling.
 * - ACCEPTED bez UPO – dociągamy UPO.
 *
 * Harmonogram: fixedDelay 5 min (start 90s po uruchomieniu). Błąd jednej faktury
 * nie zatrzymuje pozostałych.
 */
@Component
class KsefRevenueRetryScheduler(
    private val repository: KsefRevenueInvoiceRepository,
    private val dispatchService: KsefRevenueDispatchService
) {
    private val log = LoggerFactory.getLogger(KsefRevenueRetryScheduler::class.java)

    companion object {
        /** Po tym czasie stan SENDING uznajemy za osierocony (restart w trakcie wysyłki). */
        private val SENDING_STALE_THRESHOLD: Duration = Duration.ofMinutes(15)

        /** Próg ostrzegawczy okna offline24 — dosłanie wymagane do końca następnego dnia roboczego. */
        private val OFFLINE24_WARNING_THRESHOLD: Duration = Duration.ofHours(20)
    }

    @Scheduled(
        fixedDelayString = "\${ksef.revenue.retry-interval-ms:300000}",
        initialDelayString = "\${ksef.revenue.retry-initial-delay-ms:90000}"
    )
    fun processPending() {
        val candidates = repository.findByKsefStatusIn(
            listOf(KsefRevenueStatus.QUEUED_RETRY, KsefRevenueStatus.SENDING, KsefRevenueStatus.SUBMITTED)
        )
        if (candidates.isEmpty()) return

        log.info("KSeF revenue retry: {} faktur do obsłużenia", candidates.size)
        val now = Instant.now()

        candidates.forEach { invoice ->
            try {
                when (invoice.ksefStatus) {
                    KsefRevenueStatus.QUEUED_RETRY -> {
                        warnIfOffline24AtRisk(invoice.invoiceNumber, invoice.firstQueuedAt, now)
                        dispatchService.dispatch(invoice)
                    }

                    KsefRevenueStatus.SENDING -> {
                        val stale = invoice.updatedAt.isBefore(now.minus(SENDING_STALE_THRESHOLD))
                        if (stale) {
                            log.warn(
                                "Faktura {} osierocona w stanie SENDING (>{} min) — powrót do kolejki retry",
                                invoice.invoiceNumber, SENDING_STALE_THRESHOLD.toMinutes()
                            )
                            invoice.markQueuedForRetry("Wysyłka przerwana (restart aplikacji)")
                            repository.save(invoice)
                        }
                    }

                    KsefRevenueStatus.SUBMITTED -> dispatchService.complete(invoice)

                    else -> Unit
                }
            } catch (e: Exception) {
                log.error("Retry faktury {} nieudany: {}", invoice.invoiceNumber, e.message, e)
            }
        }
    }

    /** Dociąga brakujące UPO dla faktur przyjętych (osobny, rzadszy przebieg). */
    @Scheduled(
        fixedDelayString = "\${ksef.revenue.upo-interval-ms:900000}",
        initialDelayString = "\${ksef.revenue.upo-initial-delay-ms:120000}"
    )
    fun fetchMissingUpo() {
        val accepted = repository.findByKsefStatusIn(listOf(KsefRevenueStatus.ACCEPTED))
            .filter { it.upoXml == null && it.sessionReference != null && it.ksefNumber != null }
        if (accepted.isEmpty()) return

        log.info("KSeF revenue UPO backfill: {} faktur bez UPO", accepted.size)
        accepted.forEach { invoice ->
            try {
                dispatchService.complete(invoice)
            } catch (e: Exception) {
                log.warn("Pobranie UPO dla {} nieudane: {}", invoice.invoiceNumber, e.message)
            }
        }
    }

    private fun warnIfOffline24AtRisk(invoiceNumber: String, firstQueuedAt: Instant?, now: Instant) {
        if (firstQueuedAt != null && firstQueuedAt.isBefore(now.minus(OFFLINE24_WARNING_THRESHOLD))) {
            log.error(
                "Faktura {} czeka na dosłanie do KSeF od {} (>{}h) — ryzyko przekroczenia " +
                    "terminu offline24 (następny dzień roboczy)",
                invoiceNumber, firstQueuedAt, OFFLINE24_WARNING_THRESHOLD.toHours()
            )
        }
    }
}
