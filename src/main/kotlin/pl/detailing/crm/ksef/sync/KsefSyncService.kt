package pl.detailing.crm.ksef.sync

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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

    @Transactional
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

            cursorRepository.save(cursor.toSuccess(now))
            log.info("KSeF sync done studio={} fetched={} skipped={}", studioId, result.fetched, result.skipped)
        } catch (e: Exception) {
            log.error("KSeF sync FAILED studio={}: {}", studioId, e.message, e)
            cursorRepository.save(cursor.toError(e.message ?: "Unknown error"))
        }
    }
}
