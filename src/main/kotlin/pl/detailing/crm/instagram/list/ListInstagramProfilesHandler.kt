package pl.detailing.crm.instagram.list

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.shared.InstagramProfileId
import pl.detailing.crm.shared.InstagramProfileStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.StudioInstagramProfileId
import java.time.Instant

data class InstagramProfileDto(
    val id: String,
    val profileId: String,
    val username: String,
    val status: InstagramProfileStatus,
    val apiError: Boolean,
    val addedAt: Instant
)

data class ListInstagramProfilesQuery(val studioId: StudioId)

@Service
class ListInstagramProfilesHandler(
    private val studioProfileRepository: StudioInstagramProfileRepository,
    private val profileRepository: InstagramProfileRepository
) {

    @Transactional(readOnly = true)
    fun handle(query: ListInstagramProfilesQuery): List<InstagramProfileDto> {
        val studioProfiles = studioProfileRepository.findByStudioId(query.studioId.value)

        if (studioProfiles.isEmpty()) return emptyList()

        val profileIds = studioProfiles.map { it.profileId }.toSet()
        val globalProfiles = profileRepository.findAllById(profileIds).associateBy { it.id }

        return studioProfiles.mapNotNull { sp ->
            val gp = globalProfiles[sp.profileId] ?: return@mapNotNull null
            InstagramProfileDto(
                id = sp.id.toString(),
                profileId = gp.id.toString(),
                username = gp.username,
                status = sp.status,
                apiError = gp.apiError,
                addedAt = sp.createdAt
            )
        }
    }
}
