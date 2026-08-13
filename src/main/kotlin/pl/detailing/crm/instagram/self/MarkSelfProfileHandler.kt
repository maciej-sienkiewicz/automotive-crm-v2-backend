package pl.detailing.crm.instagram.self

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.StudioInstagramProfileId
import java.time.Instant

data class MarkSelfProfileCommand(
    val studioId: StudioId,
    val studioProfileId: StudioInstagramProfileId,
    /** true = oznacz jako własny profil studia; false = odznacz. */
    val isSelf: Boolean
)

/**
 * Oznacza obserwowany profil jako "Twoje studio" – punkt odniesienia benchmarku.
 * Maksymalnie jeden profil per studio; oznaczenie nowego zdejmuje flagę z poprzedniego.
 */
@Service
class MarkSelfProfileHandler(
    private val studioProfileRepository: StudioInstagramProfileRepository
) {

    @Transactional
    fun handle(command: MarkSelfProfileCommand) {
        val studioProfile = studioProfileRepository.findByStudioIdAndId(
            command.studioId.value,
            command.studioProfileId.value
        ) ?: throw EntityNotFoundException(
            "Profil o id=${command.studioProfileId} nie istnieje w tym studio."
        )

        val now = Instant.now()

        if (command.isSelf) {
            studioProfileRepository.findByStudioIdAndIsSelfTrue(command.studioId.value)?.let { current ->
                if (current.id != studioProfile.id) {
                    current.isSelf = false
                    current.updatedAt = now
                    studioProfileRepository.save(current)
                }
            }
        }

        studioProfile.isSelf = command.isSelf
        studioProfile.updatedAt = now
        studioProfileRepository.save(studioProfile)
    }
}
