package pl.detailing.crm.instagram.sync

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Codzienny scheduler pobierający szczegóły i metryki aktywnych profili.
 *
 * Uruchamia się codziennie o 08:00 – przed story sync (09:00), co gwarantuje
 * że instagramUserId jest zapisany w profilu zanim story sync spróbuje go użyć.
 *
 * Wyłączenie: instagram.details.sync.enabled=false
 */
@Component
class InstagramProfileDetailsSyncScheduler(
    private val detailsSyncService: InstagramProfileDetailsSyncService
) {
    private val log = LoggerFactory.getLogger(InstagramProfileDetailsSyncScheduler::class.java)

    @Scheduled(cron = "\${instagram.details.sync.cron:0 0 8 * * *}")
    fun syncDaily() {
        log.info("Instagram details sync: start (dzienny scheduler)")
        try {
            detailsSyncService.syncAllActiveProfiles()
        } catch (e: Exception) {
            log.error("Instagram details sync: nieoczekiwany błąd globalny: {}", e.message, e)
        }
        log.info("Instagram details sync: zakończono")
    }
}
