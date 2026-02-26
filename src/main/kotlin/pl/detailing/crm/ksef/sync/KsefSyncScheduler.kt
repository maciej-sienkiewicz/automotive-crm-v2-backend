package pl.detailing.crm.ksef.sync

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.detailing.crm.ksef.credentials.KsefCredentialsRepository
import pl.detailing.crm.shared.StudioId

/**
 * Cyklicznie synchronizuje faktury KSeF dla wszystkich studiów, które mają skonfigurowane
 * dane dostępowe (ksef_credentials).
 *
 * Harmonogram:
 * - initialDelay = 60s  – czekamy minutę po starcie aplikacji zanim wystartuje sync
 * - fixedDelay   = 15m  – następny sync zaczyna się 15 minut PO zakończeniu poprzedniego
 *   (fixedDelay, nie fixedRate, żeby uniknąć nakładania się runów)
 *
 * Multi-tenant:
 * - Studia synchronizowane są sekwencyjnie
 * - Błąd jednego studia nie wpływa na pozostałe (try/catch per studio)
 *
 * Wyłączenie synchronizacji możliwe przez ustawienie:
 *   ksef.sync.enabled=false
 * (domyślnie włączone)
 */
@Component
class KsefSyncScheduler(
    private val syncService: KsefSyncService,
    private val credentialsRepository: KsefCredentialsRepository
) {
    private val log = LoggerFactory.getLogger(KsefSyncScheduler::class.java)

    @Scheduled(
        fixedDelayString = "\${ksef.sync.interval-ms:900000}",
        initialDelayString = "\${ksef.sync.initial-delay-ms:60000}"
    )
    fun syncAllTenants() {
        val studioIds = credentialsRepository.findAllStudioIds()

        if (studioIds.isEmpty()) {
            log.debug("KSeF periodic sync: no studios with credentials configured, skipping")
            return
        }

        log.info("KSeF periodic sync started for {} studio(s)", studioIds.size)

        var successCount = 0
        var errorCount = 0

        studioIds.forEach { studioId ->
            try {
                syncService.syncStudio(StudioId(studioId))
                successCount++
            } catch (e: Exception) {
                // Błąd pojedynczego tenanta nie zatrzymuje reszty
                log.error("Unexpected error in KSeF sync for studio={}: {}", studioId, e.message, e)
                errorCount++
            }
        }

        log.info(
            "KSeF periodic sync finished: success={} errors={}",
            successCount, errorCount
        )
    }
}
