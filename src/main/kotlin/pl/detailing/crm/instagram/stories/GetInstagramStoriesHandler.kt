package pl.detailing.crm.instagram.stories

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.InstagramStorySnapshotRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.InstagramProfileStatus
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

data class InstagramStoryDto(
    val storyId: String,
    val imageUrl: String?,
    val takenAt: Instant,
    val profileId: String,
    val username: String
)

/**
 * @param studioId      Studio wykonujące zapytanie
 * @param profileId     Opcjonalny filtr – UUID globalnego profilu (instagram_profiles.id).
 *                      Null = zwróć stories ze wszystkich śledzonych profili studia.
 * @param hoursBack     Okno czasowe wstecz (domyślnie 24); stories starsze są pomijane.
 */
data class GetInstagramStoriesQuery(
    val studioId: StudioId,
    val profileId: UUID?,
    val hoursBack: Long
)

@Service
class GetInstagramStoriesHandler(
    private val studioProfileRepository: StudioInstagramProfileRepository,
    private val profileRepository: InstagramProfileRepository,
    private val storyRepository: InstagramStorySnapshotRepository
) {

    @Transactional(readOnly = true)
    fun handle(query: GetInstagramStoriesQuery): List<InstagramStoryDto> {
        // Wszystkie profile śledzone przez studio (niezależnie od statusu – studio może chcieć
        // podejrzeć stories nawet dla profilu PENDING, ale walidujemy przynależność)
        val studioProfiles = studioProfileRepository.findByStudioId(query.studioId.value)

        if (studioProfiles.isEmpty()) return emptyList()

        val allowedProfileIds = studioProfiles.map { it.profileId }.toSet()

        // Walidacja filtra po konkretnym profilu
        if (query.profileId != null && query.profileId !in allowedProfileIds) {
            throw ForbiddenException(
                "Profil ${query.profileId} nie jest śledzony przez to studio."
            )
        }

        val profileIdsToQuery: Set<UUID> = if (query.profileId != null) {
            setOf(query.profileId)
        } else {
            allowedProfileIds
        }

        val cutoff = Instant.now().minus(query.hoursBack, ChronoUnit.HOURS)

        // Pobierz stories dla każdego profilu i odfiltruj po oknie czasowym
        val stories = profileIdsToQuery.flatMap { profileId ->
            storyRepository.findByProfileIdOrderByTakenAtDesc(profileId)
                .filter { it.takenAt.isAfter(cutoff) }
        }

        if (stories.isEmpty()) return emptyList()

        // Załaduj dane profili (username) dla wyników
        val globalProfiles = profileRepository.findAllById(profileIdsToQuery).associateBy { it.id }

        return stories
            .mapNotNull { story ->
                val profile = globalProfiles[story.profileId] ?: return@mapNotNull null
                InstagramStoryDto(
                    storyId = story.storyId,
                    imageUrl = story.imageUrl,
                    takenAt = story.takenAt,
                    profileId = story.profileId.toString(),
                    username = profile.username
                )
            }
            .sortedBy { it.takenAt }
    }
}
