package pl.detailing.crm.instagram.sync

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository

/**
 * Nasłuchuje na zatwierdzenie profilu i natychmiast pobiera jego posty z API.
 *
 * Używa @TransactionalEventListener(AFTER_COMMIT) + @Async, żeby:
 * 1. Sync startuje dopiero po commicie transakcji zatwierdzenia (profil widoczny w DB).
 * 2. Wykonuje się w osobnym wątku – HTTP approval wraca od razu, bez czekania na API.
 */
@Component
class InstagramApprovalSyncListener(
    private val profileRepository: InstagramProfileRepository,
    private val syncService: InstagramSyncService
) {
    private val log = LoggerFactory.getLogger(InstagramApprovalSyncListener::class.java)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onProfileApproved(event: InstagramProfileApprovedEvent) {
        log.info(
            "Instagram sync: natychmiastowe pobieranie postów dla @{} po zatwierdzeniu",
            event.username
        )

        val profile = profileRepository.findById(event.profileId).orElse(null)
        if (profile == null) {
            log.warn("Instagram sync: nie znaleziono profilu id={} – pomijam sync", event.profileId)
            return
        }

        try {
            syncService.syncProfile(profile)
            log.info("Instagram sync: zakończono natychmiastowy sync dla @{}", event.username)
        } catch (e: Exception) {
            log.error(
                "Instagram sync: błąd natychmiastowego sync dla @{}: {}",
                event.username, e.message, e
            )
        }
    }
}
