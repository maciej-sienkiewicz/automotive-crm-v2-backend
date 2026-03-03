package pl.detailing.crm.instagram.reject

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.InstagramProfileStatus
import java.time.Instant

@Service
class RejectInstagramProfileHandler(
    private val studioProfileRepository: StudioInstagramProfileRepository
) {

    @Transactional
    fun handle(command: RejectInstagramProfileCommand) {
        val studioProfile = studioProfileRepository.findByStudioIdAndId(
            command.studioId.value,
            command.studioProfileId.value
        ) ?: throw EntityNotFoundException(
            "Profil o id=${command.studioProfileId} nie istnieje w tym studio."
        )

        studioProfile.status = InstagramProfileStatus.REJECTED
        studioProfile.updatedAt = Instant.now()
        studioProfileRepository.save(studioProfile)
    }
}
