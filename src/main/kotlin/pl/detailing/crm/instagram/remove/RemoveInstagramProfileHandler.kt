package pl.detailing.crm.instagram.remove

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.InstagramProfileStatus

@Service
class RemoveInstagramProfileHandler(
    private val studioProfileRepository: StudioInstagramProfileRepository,
    private val profileRepository: InstagramProfileRepository,
    private val postSnapshotRepository: InstagramPostSnapshotRepository
) {

    @Transactional
    fun handle(command: RemoveInstagramProfileCommand) {
        val studioProfile = studioProfileRepository.findByStudioIdAndId(
            command.studioId.value,
            command.studioProfileId.value
        ) ?: throw EntityNotFoundException(
            "Profil o id=${command.studioProfileId} nie istnieje w tym studio."
        )

        val profileId = studioProfile.profileId
        studioProfileRepository.delete(studioProfile)

        // Jeśli żadne studio nie ma już tego profilu (w jakimkolwiek statusie),
        // usuń globalny profil i jego snapshoty postów.
        val remainingSubscriptions = studioProfileRepository.countByProfileIdAndStatus(
            profileId,
            InstagramProfileStatus.ACTIVE
        ) + studioProfileRepository.countByProfileIdAndStatus(
            profileId,
            InstagramProfileStatus.PENDING_APPROVAL
        )

        if (remainingSubscriptions == 0L) {
            postSnapshotRepository.deleteByProfileId(profileId)
            profileRepository.deleteById(profileId)
        }
    }
}
