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
import pl.detailing.crm.livemetrics.BusinessEventPublisher
import pl.detailing.crm.livemetrics.domain.BusinessEventType

data class AddInstagramProfileResult(
    val studioProfileId: StudioInstagramProfileId,
    val profileId: InstagramProfileId,
    val username: String,
    val status: InstagramProfileStatus
)

@Service
class AddInstagramProfileHandler(
    private val profileRepository: InstagramProfileRepository,
    private val studioProfileRepository: StudioInstagramProfileRepository,
    private val businessEventPublisher: BusinessEventPublisher
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

        // Kont prywatnych nie monitorujemy (decyzja prawna – analizujemy wyłącznie
        // publiczną działalność marketingową firm)
        profileRepository.findByUsername(normalised)?.let { existing ->
            if (existing.isPrivate) {
                throw ValidationException(
                    "Profil @$normalised jest kontem prywatnym – monitorujemy wyłącznie publiczne profile firmowe."
                )
            }
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

        // Live metrics — studio rozszerza listę obserwowanych profili
        businessEventPublisher.publish(
            tenantId = command.studioId,
            type = BusinessEventType.INSTAGRAM_PROFILE_ADDED,
            attributes = mapOf("username" to globalProfile.username, "userId" to command.userId.value.toString())
        )

        return AddInstagramProfileResult(
            studioProfileId = StudioInstagramProfileId(studioProfile.id),
            profileId = InstagramProfileId(globalProfile.id),
            username = globalProfile.username,
            status = studioProfile.status
        )
    }
}
