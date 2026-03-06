package pl.detailing.crm.instagram.approve

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.instagram.sync.InstagramProfileApprovedEvent
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.InstagramProfileStatus
import java.time.Instant

@Service
class ApproveInstagramProfileHandler(
    private val studioProfileRepository: StudioInstagramProfileRepository,
    private val profileRepository: InstagramProfileRepository,
    private val eventPublisher: ApplicationEventPublisher
) {

    @Transactional
    fun handle(command: ApproveInstagramProfileCommand) {
        val studioProfile = studioProfileRepository.findByStudioIdAndId(
            command.studioId.value,
            command.studioProfileId.value
        ) ?: throw EntityNotFoundException(
            "Profil o id=${command.studioProfileId} nie istnieje w tym studio."
        )

        studioProfile.status = InstagramProfileStatus.ACTIVE
        studioProfile.updatedAt = Instant.now()
        studioProfileRepository.save(studioProfile)

        // Pobierz globalny profil żeby przekazać username do eventu (tylko logowanie)
        val globalProfile = profileRepository.findById(studioProfile.profileId).orElse(null)
        val username = globalProfile?.username ?: studioProfile.profileId.toString()

        // Emituj event – nasłuchiwacz uruchomi sync po commicie tej transakcji
        eventPublisher.publishEvent(
            InstagramProfileApprovedEvent(
                profileId = studioProfile.profileId,
                username = username
            )
        )
    }
}
