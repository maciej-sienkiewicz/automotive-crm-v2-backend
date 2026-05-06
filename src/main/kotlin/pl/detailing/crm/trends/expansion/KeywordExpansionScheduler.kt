package pl.detailing.crm.trends.expansion

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.detailing.crm.trends.repository.SyncStatusRepository

/**
 * Runs keyword expansion once a month (1st of each month at 02:00).
 *
 * Cost per run: ceil(activeKeywords / 20) × $0.075
 * With 100 active keywords: 5 requests × $0.075 = $0.375/month.
 */
@Component
class KeywordExpansionScheduler(
    private val expansionService: KeywordExpansionService,
    private val syncRepo: SyncStatusRepository
) {
    private val log = LoggerFactory.getLogger(KeywordExpansionScheduler::class.java)

    companion object {
        const val TASK_KEYWORD_EXPANSION = "KEYWORD_EXPANSION"
    }

    @Scheduled(cron = "0 0 2 1 * *")
    fun monthlyExpansion() {
        if (isAlreadyRunning()) {
            log.warn("Keyword expansion already running — skipping.")
            return
        }
        try {
            syncRepo.markRunning(TASK_KEYWORD_EXPANSION)
            expansionService.expand()
            syncRepo.markSuccess(TASK_KEYWORD_EXPANSION, "Top-${KeywordExpansionService.TOP_KEYWORDS_LIMIT} re-evaluated")
        } catch (ex: Exception) {
            log.error("Keyword expansion failed.", ex)
            syncRepo.markFailed(TASK_KEYWORD_EXPANSION, ex.message)
        }
    }

    private fun isAlreadyRunning(): Boolean =
        syncRepo.find(TASK_KEYWORD_EXPANSION)?.status?.name == "RUNNING"
}
