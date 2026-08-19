package pl.detailing.crm.metrics.rollup

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.metrics.config.MetricsProperties
import pl.detailing.crm.metrics.domain.MetricsClock
import pl.detailing.crm.metrics.infrastructure.*
import java.time.Instant

/**
 * Purges raw metric rows once they have been rolled up.
 *
 * ## Why this is not optional
 *
 * A metrics module without retention is a slow-motion outage: the raw tables grow without
 * bound, backups lengthen, and eventually the analytics data the business barely looks at
 * is the largest thing in the database that holds its customers' invoices. The aggregates
 * are what anyone actually queries, and they are permanent — the history survives, only
 * the row-level detail behind it expires.
 *
 * ## Two things are deliberately never purged
 *
 * - **Daily snapshots**, because they *are* the history.
 * - **Error groups and their per-tenant impact rows**, because "this defect first hit this
 *   customer in March" is exactly the fact that matters in a support escalation, long
 *   after the individual stack traces have stopped being useful.
 *
 * It also runs *after* the roll-ups (03:40 vs 03:10 / 03:25). Reversed, it would delete a
 * day's raw data before that day had been aggregated, and the loss would be permanent and
 * invisible until someone opened last month's chart.
 */
@Component
class MetricsRetentionJob(
    private val eventRepository: MetricEventRepository,
    private val sessionRepository: UserSessionRepository,
    private val errorEventRepository: ErrorEventRepository,
    private val apiDailyRepository: ApiEndpointDailyRepository,
    private val studioApiDailyRepository: StudioApiDailyRepository,
    private val properties: MetricsProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${crm.metrics.retention.cron:0 40 3 * * *}")
    @Transactional
    fun purge() {
        if (!properties.enabled) return

        val retention = properties.retention
        try {
            val events = eventRepository.deleteOlderThan(daysAgo(retention.eventDays))
            val sessions = sessionRepository.deleteOlderThan(daysAgo(retention.sessionDays))
            val errors = errorEventRepository.deleteOlderThan(daysAgo(retention.errorEventDays))
            val apiDaily = apiDailyRepository.deleteOlderThan(
                MetricsClock.today().minusDays(retention.apiUsageDays)
            )
            val studioApiDaily = studioApiDailyRepository.deleteOlderThan(
                MetricsClock.today().minusDays(retention.apiUsageDays)
            )

            log.info(
                "Retencja metryk: usunięto {} zdarzeń, {} sesji, {} błędów, {} + {} wierszy ruchu API",
                events, sessions, errors, apiDaily, studioApiDaily
            )
        } catch (ex: Exception) {
            log.error("Retencja metryk nie powiodła się: {}", ex.message, ex)
        }
    }

    private fun daysAgo(days: Long): Instant = Instant.now().minusSeconds(days * 86_400)
}
