package pl.detailing.crm.instagram.sync

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Codzienny scheduler pobierający stories aktywnych profili konkurencji.
 *
 * Uruchamia się codziennie o 09:00 czasu serwera.
 * Cron Spring (6 pól): sekunda minuta godzina dzień miesiąc dzień-tygodnia
 *   "0 0 9 * * *"
 *
 * Wyłączenie możliwe przez ustawienie:
 *   instagram.stories.sync.enabled=false
 */
@Component
class InstagramStorySyncScheduler(
    private val storySyncService: InstagramStorySyncService
) {
    private val log = LoggerFactory.getLogger(InstagramStorySyncScheduler::class.java)

    @Scheduled(cron = "\${instagram.stories.sync.cron:0 0 9 * * *}")
    fun syncDaily() {
        log.info("Instagram stories sync: start (dzienny scheduler)")
        try {
            storySyncService.syncAllActiveProfiles()
        } catch (e: Exception) {
            log.error("Instagram stories sync: nieoczekiwany błąd globalny: {}", e.message, e)
        }
        log.info("Instagram stories sync: zakończono")
    }
}
