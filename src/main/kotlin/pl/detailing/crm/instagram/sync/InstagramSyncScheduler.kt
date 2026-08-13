package pl.detailing.crm.instagram.sync

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Harmonogramy synchronizacji Instagrama.
 *
 * - Tygodniowy (niedziela 03:00): głęboki sync postów + pełny przebieg insightów.
 *   Cron: instagram.sync.cron, wyłącznik: instagram.sync.enabled.
 * - Dzienny (06:30): szczegóły profili (snapshot obserwujących) + 1 strona postów
 *   (świeże posty, promocje wykrywane w ≤24h).
 *   Cron: instagram.daily-sync.cron, wyłącznik: instagram.daily-sync.enabled.
 */
@Component
class InstagramSyncScheduler(
    private val orchestrator: InstagramSyncOrchestrator,
    @Value("\${instagram.sync.enabled:true}") private val weeklyEnabled: Boolean,
    @Value("\${instagram.daily-sync.enabled:true}") private val dailyEnabled: Boolean
) {
    private val log = LoggerFactory.getLogger(InstagramSyncScheduler::class.java)

    @Scheduled(cron = "\${instagram.sync.cron:0 0 3 * * SUN}")
    fun syncWeekly() {
        if (!weeklyEnabled) {
            log.info("Instagram weekly sync: wyłączony (instagram.sync.enabled=false)")
            return
        }
        try {
            orchestrator.weeklySyncAll()
        } catch (e: Exception) {
            log.error("Instagram weekly sync: nieoczekiwany błąd globalny: {}", e.message, e)
        }
    }

    @Scheduled(cron = "\${instagram.daily-sync.cron:0 30 6 * * *}")
    fun syncDaily() {
        if (!dailyEnabled) {
            log.info("Instagram daily sync: wyłączony (instagram.daily-sync.enabled=false)")
            return
        }
        try {
            orchestrator.dailySyncAll()
        } catch (e: Exception) {
            log.error("Instagram daily sync: nieoczekiwany błąd globalny: {}", e.message, e)
        }
    }
}
