package pl.detailing.crm.instagram.sync

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository

/**
 * Po zatwierdzeniu profilu natychmiast uruchamia pełny pierwszy sync
 * (szczegóły → posty 12 mies. → tematy → agregaty → insighty).
 *
 * @TransactionalEventListener(AFTER_COMMIT) + @Async: approval wraca od razu,
 * pobieranie danych dzieje się w tle po commicie.
 */
@Component
class InstagramApprovalSyncListener(
    private val profileRepository: InstagramProfileRepository,
    private val orchestrator: InstagramSyncOrchestrator
) {
    private val log = LoggerFactory.getLogger(InstagramApprovalSyncListener::class.java)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onProfileApproved(event: InstagramProfileApprovedEvent) {
        val profile = profileRepository.findById(event.profileId).orElse(null)
        if (profile == null) {
            log.warn("Instagram sync: nie znaleziono profilu id={} – pomijam sync", event.profileId)
            return
        }
        orchestrator.initialSync(profile)
    }
}
