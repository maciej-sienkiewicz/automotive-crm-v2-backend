package pl.detailing.crm.ksef.sync

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.ksef.credentials.KsefCredentialsRepository
import pl.detailing.crm.ksef.fetch.FetchExpensesCommand
import pl.detailing.crm.ksef.fetch.FetchKsefInvoicesHandler
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
 * Each run also performs a backward sync (backfill): invoices fetched before line items
 * and detail data were introduced (details_synced = FALSE) get their missing data pulled
 * from the invoice XML, regardless of the delta window.
 */
@Service
class KsefSyncService(
    private val fetchHandler: FetchKsefInvoicesHandler,
    private val cursorRepository: KsefSyncCursorRepository,
    private val credentialsRepository: KsefCredentialsRepository
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

            cursorRepository.save(cursor.toSuccess(now))
            log.info(
                "KSeF sync done studio={} fetched={} skipped={} backfilled={}",
                studioId, result.fetched, result.skipped, backfilled
            )
        } catch (e: Exception) {
            log.error("KSeF sync FAILED studio={}: {}", studioId, e.message, e)
            cursorRepository.save(cursor.toError(e.message ?: "Unknown error"))
        }
    }
}
