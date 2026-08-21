package pl.detailing.crm.ksef.sync

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.finance.duplicates.DocumentDuplicateDetector
import pl.detailing.crm.ksef.credentials.KsefCredentialsRepository
import pl.detailing.crm.ksef.fetch.FetchExpensesCommand
import pl.detailing.crm.ksef.fetch.FetchKsefInvoicesHandler
import pl.detailing.crm.ksef.revenue.sync.FetchRevenueCommand
import pl.detailing.crm.ksef.revenue.sync.FetchRevenueInvoicesHandler
import pl.detailing.crm.shared.StudioId
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Delta-sync of KSeF expense invoices for a single studio.
 *
 * First sync: starts from credentials.createdAt to avoid pulling pre-integration history.
 * Subsequent syncs: starts from (lastExpenseSync - 1h) for reliability against KSeF delays.
 *
 * Each run also performs a backward sync (backfill), on both sides of the ledger:
 * invoices stored without their XML details (details_synced = FALSE) get line items,
 * party addresses and payment data pulled from the invoice XML, regardless of the
 * delta window. Both sides draw on one shared KSeF request budget.
 */
@Service
class KsefSyncService(
    private val fetchHandler: FetchKsefInvoicesHandler,
    private val revenueFetchHandler: FetchRevenueInvoicesHandler,
    private val cursorRepository: KsefSyncCursorRepository,
    private val credentialsRepository: KsefCredentialsRepository,
    private val duplicateDetector: DocumentDuplicateDetector
) {
    private val log = LoggerFactory.getLogger(KsefSyncService::class.java)

    companion object {
        private const val OVERLAP_HOURS = 1L
        private const val PAGE_SIZE = 100
        private val STALE_THRESHOLD = Duration.ofMinutes(30)
    }

    /**
     * Celowo BEZ @Transactional: fetch i backfill mają własne transakcje w handlerze.
     * Wspólna transakcja powodowała, że wyjątek z handlera oznaczał ją jako rollback-only
     * i zapis statusu ERROR na kursorze ginął (UnexpectedRollbackException przy commicie).
     */
    fun syncStudio(studioId: StudioId) {
        val cursor = cursorRepository.findById(studioId.value)
            .orElse(KsefSyncCursorEntity(studioId = studioId.value))

        if (cursor.syncStatus == "RUNNING") {
            if (cursor.isStale(STALE_THRESHOLD)) {
                log.warn("KSeF sync stale (>{}min) for studio={}, resetting", STALE_THRESHOLD.toMinutes(), studioId)
                cursorRepository.save(cursor.toIdle())
            } else {
                log.warn("KSeF sync already RUNNING for studio={}, skipping", studioId)
                return
            }
        }

        val credentials = credentialsRepository.findByStudioId(studioId.value) ?: run {
            log.warn("No KSeF credentials for studio={}, skipping", studioId)
            return
        }

        val integrationStart = credentials.createdAt.atOffset(ZoneOffset.UTC)
        cursorRepository.save(cursor.toRunning())
        val now = OffsetDateTime.now()

        try {
            val dateFrom = cursor.lastExpenseSync
                ?.minusHours(OVERLAP_HOURS)
                ?.let { if (it.isBefore(integrationStart)) integrationStart else it }
                ?: integrationStart

            log.info("KSeF sync studio={} EXPENSE from={}", studioId, dateFrom)

            val result = fetchHandler.handle(
                FetchExpensesCommand(studioId = studioId, dateFrom = dateFrom, dateTo = now, pageSize = PAGE_SIZE)
            )

            // Synchronizacja wsteczna: uzupełnia pozycje i szczegóły faktur pobranych
            // przed wprowadzeniem tych danych — niezależnie od okna dat delta-syncu
            val backfilled = fetchHandler.backfillMissingDetails(studioId)

            // Pull przychodów (SUBJECT1): kompletność statystyk przychodowych także dla
            // faktur wystawionych poza CRM + detekcja podwójnego fakturowania
            val revenueFrom = cursor.lastRevenueSync
                ?.minusHours(OVERLAP_HOURS)
                ?.let { if (it.isBefore(integrationStart)) integrationStart else it }
                ?: integrationStart
            log.info("KSeF sync studio={} REVENUE from={}", studioId, revenueFrom)
            val revenueResult = revenueFetchHandler.handle(
                FetchRevenueCommand(studioId = studioId, dateFrom = revenueFrom, dateTo = now, pageSize = PAGE_SIZE)
            )

            // Ta sama synchronizacja wsteczna po stronie przychodów: faktury zewnętrzne
            // zapisane bez XML (wyczerpany budżet żądań albo pobranie sprzed wdrożenia
            // tej ścieżki) dostają pozycje i szczegóły płatności
            val revenueBackfilled = revenueFetchHandler.backfillMissingDetails(studioId)

            // Obie strony ewentualnej pary są już w bazie, więc dopiero teraz ma sens
            // szukanie dokumentów opisujących tę samą sprzedaż albo zakup dwa razy.
            // Detekcja nie może wywrócić synchronizacji: pull się udał, kursor ma
            // ruszyć do przodu niezależnie od tego, czy dopasowanie się powiodło.
            val duplicates = runCatching { duplicateDetector.scanRecent(studioId) }
                .onFailure { log.error("Detekcja duplikatów nie powiodła się studio={}: {}", studioId, it.message, it) }
                .getOrDefault(0)

            cursorRepository.save(cursor.toSuccess(now))
            log.info(
                "KSeF sync done studio={} expenses: fetched={} skipped={} backfilled={} | " +
                    "revenue: external={} matched={} duplicatesSuspected={} backfilled={} | zdublowane wyciszone={}",
                studioId, result.fetched, result.skipped, backfilled,
                revenueResult.fetched, revenueResult.matched, revenueResult.duplicatesSuspected,
                revenueBackfilled, duplicates
            )
        } catch (e: Exception) {
            log.error("KSeF sync FAILED studio={}: {}", studioId, e.message, e)
            cursorRepository.save(cursor.toError(e.message ?: "Unknown error"))
        }
    }
}
