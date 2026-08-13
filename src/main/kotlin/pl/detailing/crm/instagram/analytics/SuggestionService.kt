package pl.detailing.crm.instagram.analytics

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramDataProvider
import pl.detailing.crm.instagram.infrastructure.InstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileSuggestionEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileSuggestionRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.shared.InstagramProfileStatus
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

data class ProfileSuggestionDto(
    val username: String,
    val fullName: String?,
    val isVerified: Boolean,
    /** Ile obserwowanych profili "poleca" to konto – im więcej, tym trafniejsza sugestia. */
    val recommendedByCount: Int,
    /** Username profilu źródłowego (do etykiety "podobny do @x"). */
    val similarTo: String
)

/**
 * Sugestie "Zaobserwuj podobne profile" – rozwiązują cold start (do benchmarku
 * potrzeba min. 3 profili) i pomagają zbudować sensowny koszyk konkurencji.
 *
 * Źródło: related-profiles dostawcy (algorytm Instagrama). Koszt quoty:
 * 1 wywołanie na profil, odświeżane najwcześniej po 30 dniach.
 */
@Service
class SuggestionService(
    private val provider: InstagramDataProvider,
    private val profileRepository: InstagramProfileRepository,
    private val studioProfileRepository: StudioInstagramProfileRepository,
    private val suggestionRepository: InstagramProfileSuggestionRepository
) {
    private val log = LoggerFactory.getLogger(SuggestionService::class.java)

    companion object {
        const val TTL_DAYS = 30L
        const val MAX_PER_PROFILE = 10
        const val MAX_FOR_STUDIO = 8
    }

    /** Pobiera i cache'uje sugestie dla profilu; pomija, gdy cache świeższy niż TTL. */
    @Transactional
    fun refreshForProfile(profile: InstagramProfileEntity, force: Boolean = false) {
        try {
            if (!force) {
                val newest = suggestionRepository.findFirstByProfileIdOrderByFetchedAtDesc(profile.id)
                if (newest != null && newest.fetchedAt.isAfter(Instant.now().minus(TTL_DAYS, ChronoUnit.DAYS))) {
                    return
                }
            }

            val related = provider.fetchRelatedProfiles(profile.username, profile.instagramUserId)
                .filter { !it.isPrivate && it.username != profile.username }
                .take(MAX_PER_PROFILE)

            suggestionRepository.deleteByProfileId(profile.id)
            if (related.isEmpty()) return

            val now = Instant.now()
            suggestionRepository.saveAll(
                related.map {
                    InstagramProfileSuggestionEntity(
                        id = UUID.randomUUID(),
                        profileId = profile.id,
                        suggestedUsername = it.username,
                        fullName = it.fullName?.take(150),
                        isVerified = it.isVerified,
                        fetchedAt = now
                    )
                }
            )
            log.info("Instagram sugestie: @{} – zapisano {} podobnych profili", profile.username, related.size)
        } catch (e: Exception) {
            // Sugestie to funkcja pomocnicza – jej błąd nigdy nie blokuje syncu
            log.warn("Instagram sugestie: błąd dla @{}: {}", profile.username, e.message)
        }
    }

    /** Sugestie dla studia: z cache profili koszyka, bez kont już obserwowanych. */
    @Transactional(readOnly = true)
    fun suggestionsForStudio(studioId: StudioId): List<ProfileSuggestionDto> {
        val links = studioProfileRepository.findByStudioId(studioId.value)
        if (links.isEmpty()) return emptyList()

        val activeIds = links.filter { it.status == InstagramProfileStatus.ACTIVE }.map { it.profileId }
        if (activeIds.isEmpty()) return emptyList()

        val observedUsernames = profileRepository.findAllById(links.map { it.profileId })
            .map { it.username }
            .toSet()
        val sourceUsernames = profileRepository.findAllById(activeIds).associate { it.id to it.username }

        return suggestionRepository.findByProfileIdIn(activeIds)
            .filter { it.suggestedUsername !in observedUsernames }
            .groupBy { it.suggestedUsername }
            .map { (username, rows) ->
                val first = rows.first()
                ProfileSuggestionDto(
                    username = username,
                    fullName = rows.firstNotNullOfOrNull { it.fullName },
                    isVerified = first.isVerified,
                    recommendedByCount = rows.map { it.profileId }.distinct().size,
                    similarTo = sourceUsernames[first.profileId] ?: ""
                )
            }
            .sortedWith(compareByDescending<ProfileSuggestionDto> { it.recommendedByCount }
                .thenByDescending { it.isVerified })
            .take(MAX_FOR_STUDIO)
    }
}
