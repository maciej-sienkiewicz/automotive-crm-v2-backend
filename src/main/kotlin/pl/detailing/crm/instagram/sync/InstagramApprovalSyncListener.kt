package pl.detailing.crm.instagram.sync

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository

/**
 * Nasłuchuje na zatwierdzenie profilu i natychmiast uruchamia pełny sync:
 * 1. Posty (pełna historia 12 miesięcy).
 * 2. Szczegóły profilu z /user/details (zapisuje instagramUserId, metryki, snapshot followerów).
 * 3. Stories (wymaga instagramUserId z kroku 2).
 *
 * Używa @TransactionalEventListener(AFTER_COMMIT) + @Async, żeby:
 * - Sync startuje dopiero po commicie transakcji zatwierdzenia (profil widoczny w DB).
 * - Wykonuje się w osobnym wątku – HTTP approval wraca od razu, bez czekania na API.
 */
@Component
class InstagramApprovalSyncListener(
    private val profileRepository: InstagramProfileRepository,
    private val syncService: InstagramSyncService,
    private val detailsSyncService: InstagramProfileDetailsSyncService,
    private val storySyncService: InstagramStorySyncService
) {
    private val log = LoggerFactory.getLogger(InstagramApprovalSyncListener::class.java)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onProfileApproved(event: InstagramProfileApprovedEvent) {
        log.info(
            "Instagram sync: natychmiastowe pobieranie danych dla @{} po zatwierdzeniu",
            event.username
        )

        val profile = profileRepository.findById(event.profileId).orElse(null)
        if (profile == null) {
            log.warn("Instagram sync: nie znaleziono profilu id={} – pomijam sync", event.profileId)
            return
        }

        // 1. Posty (pełna historia 12 miesięcy)
        try {
            syncService.syncProfile(profile)
            log.info("Instagram sync: zakończono sync postów dla @{}", event.username)
        } catch (e: Exception) {
            log.error("Instagram sync: błąd sync postów dla @{}: {}", event.username, e.message, e)
        }

        // 2. Szczegóły profilu (zapisuje instagramUserId + metryki)
        try {
            detailsSyncService.syncProfile(profile)
            log.info("Instagram sync: zakończono sync szczegółów dla @{}", event.username)
        } catch (e: Exception) {
            log.error("Instagram sync: błąd sync szczegółów dla @{}: {}", event.username, e.message, e)
        }

        // 3. Stories (wymaga instagramUserId z kroku 2)
        try {
            storySyncService.syncProfile(profile)
            log.info("Instagram sync: zakończono sync stories dla @{}", event.username)
        } catch (e: Exception) {
            log.error("Instagram sync: błąd sync stories dla @{}: {}", event.username, e.message, e)
        }
    }
}
