package pl.detailing.crm.instagram.add

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.shared.*
import java.time.Instant
import java.util.*

data class AddInstagramProfileResult(
    val studioProfileId: StudioInstagramProfileId,
    val profileId: InstagramProfileId,
    val username: String,
    val status: InstagramProfileStatus
)

@Service
class AddInstagramProfileHandler(
    private val profileRepository: InstagramProfileRepository,
    private val studioProfileRepository: StudioInstagramProfileRepository
) {

    /** Dopuszczalne znaki w nazwie użytkownika Instagram: litery, cyfry, _ i . */
    private val usernameRegex = Regex("^[a-zA-Z0-9._]{1,30}$")

    @Transactional
    fun handle(command: AddInstagramProfileCommand): AddInstagramProfileResult {
        val normalised = command.username.trim().lowercase()

        if (!usernameRegex.matches(normalised)) {
            throw ValidationException(
                "Nieprawidłowy format nazwy użytkownika Instagram. " +
                "Dozwolone znaki: litery, cyfry, _ i . (maks. 30 znaków)."
            )
        }

        // Pobierz lub utwórz globalny profil
        val globalProfile = profileRepository.findByUsername(normalised)
            ?: profileRepository.save(
                InstagramProfileEntity(
                    id = UUID.randomUUID(),
                    username = normalised
                )
            )

        // Sprawdź czy studio już obserwuje ten profil
        if (studioProfileRepository.existsByStudioIdAndProfileId(
                command.studioId.value,
                globalProfile.id
            )
        ) {
            throw ConflictException("Profil @$normalised jest już dodany do Twojego listy obserwowanych.")
        }

        val studioProfile = studioProfileRepository.save(
            StudioInstagramProfileEntity(
                id = UUID.randomUUID(),
                studioId = command.studioId.value,
                profileId = globalProfile.id,
                status = InstagramProfileStatus.PENDING_APPROVAL,
                addedByUserId = command.userId.value,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
        )

        return AddInstagramProfileResult(
            studioProfileId = StudioInstagramProfileId(studioProfile.id),
            profileId = InstagramProfileId(globalProfile.id),
            username = globalProfile.username,
            status = studioProfile.status
        )
    }
}
