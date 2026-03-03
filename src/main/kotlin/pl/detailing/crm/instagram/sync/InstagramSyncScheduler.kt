package pl.detailing.crm.instagram.sync

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Harmonogram tygodniowej synchronizacji danych z Instagrama.
 *
 * Uruchamia się w każdą niedzielę o 03:00 czasu serwera.
 * Cron Spring (6 pól): sekunda minuta godzina dzień miesiąc dzień-tygodnia
 *   "0 0 3 * * SUN"
 *
 * Wyłączenie synchronizacji możliwe przez ustawienie:
 *   instagram.sync.enabled=false
 * (domyślnie włączone)
 */
@Component
class InstagramSyncScheduler(
    private val syncService: InstagramSyncService
) {
    private val log = LoggerFactory.getLogger(InstagramSyncScheduler::class.java)

    @Scheduled(cron = "\${instagram.sync.cron:0 0 3 * * SUN}")
    fun syncWeekly() {
        log.info("Instagram competitor sync: start (niedzielna synchronizacja)")
        try {
            syncService.syncAllActiveProfiles()
        } catch (e: Exception) {
            log.error("Instagram competitor sync: nieoczekiwany błąd globalny: {}", e.message, e)
        }
        log.info("Instagram competitor sync: zakończono")
    }
}
