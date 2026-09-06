package pl.detailing.crm.instagram.ads

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Codzienne odpytanie Biblioteki reklam.
 *
 * Częściej nie ma sensu: reklamy trwają tygodniami, a jedyne zdarzenie, którego
 * nie wolno przegapić — zakończenie emisji — i tak raportujemy z datą naszego
 * odczytu. Doba to zarazem rozdzielczość kalendarza, który z tego rysujemy.
 */
@Component
class MetaAdsSyncScheduler(
    private val syncService: MetaAdsSyncService,
    @Value("\${meta.ads.sync.enabled:true}") private val enabled: Boolean
) {
    private val log = LoggerFactory.getLogger(MetaAdsSyncScheduler::class.java)

    @Scheduled(cron = "\${meta.ads.sync.cron:0 45 5 * * *}")
    fun sync() {
        if (!enabled) return
        try {
            syncService.syncAll()
        } catch (e: Exception) {
            log.error("Meta Ad Library: nieoczekiwany błąd synchronizacji: {}", e.message, e)
        }
    }
}
