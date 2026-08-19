package pl.detailing.crm.metrics.session

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.detailing.crm.metrics.config.MetricsProperties
import pl.detailing.crm.metrics.domain.SessionEndReason
import pl.detailing.crm.metrics.infrastructure.UserSessionRepository
import java.time.Instant

/**
 * Closes sessions that stopped reporting in.
 *
 * Without this job every session that ends by closing the laptop rather than clicking
 * "Wyloguj" stays open forever, and both the "sessions today" and "time spent" numbers
 * drift upward month after month. With it — and because closure is retroactive to the
 * last heartbeat — the dead tail between the user leaving and the sweep is discarded
 * rather than credited.
 *
 * Runs every minute; the work is a single indexed query that normally returns nothing.
 */
@Component
class SessionSweeper(
    private val repository: UserSessionRepository,
    private val tracker: SessionActivityTracker,
    private val properties: MetricsProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${crm.metrics.session.sweeper-cron:0 * * * * *}")
    fun sweep() {
        if (!properties.enabled) return

        try {
            val threshold = Instant.now().minusSeconds(properties.session.timeoutSeconds)
            val stale = repository.findStale(threshold)
            if (stale.isEmpty()) return

            stale.forEach { tracker.closeRetroactively(it, SessionEndReason.TIMEOUT) }

            val meaningful = stale.count { it.isMeaningful }
            log.debug(
                "Zamknięto {} nieaktywnych sesji ({} znaczących, {} pustych)",
                stale.size, meaningful, stale.size - meaningful
            )
        } catch (ex: Exception) {
            log.error("Zamykanie nieaktywnych sesji nie powiodło się: {}", ex.message, ex)
        }
    }
}
