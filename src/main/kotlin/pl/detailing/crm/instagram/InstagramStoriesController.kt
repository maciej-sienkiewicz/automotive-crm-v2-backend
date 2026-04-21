package pl.detailing.crm.instagram

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.instagram.stories.GetInstagramStoriesHandler
import pl.detailing.crm.instagram.stories.GetInstagramStoriesQuery
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.*

@RestController
@RequestMapping("/api/v1/instagram/stories")
class InstagramStoriesController(
    private val storiesHandler: GetInstagramStoriesHandler
) {

    /**
     * Pobierz stories z aktywnych profili konkurencji śledzonych przez studio.
     *
     * GET /api/v1/instagram/stories?hoursBack=24&profileId=<uuid>
     *
     * Parametry:
     * - hoursBack  – okno czasowe wstecz w godzinach (domyślnie 24, min 1, max 8760 = 1 rok)
     * - profileId  – (opcjonalny) UUID globalnego profilu; bez filtra = wszystkie śledzone profile
     *
     * Walidacja: profileId musi należeć do profili śledzonych przez studio użytkownika.
     * Wyniki posortowane od najstarszego do najnowszego (rosnąco po takenAt).
     */
    @GetMapping
    fun getStories(
        @RequestParam(defaultValue = "24") hoursBack: Long,
        @RequestParam(required = false) profileId: String?
    ): ResponseEntity<List<InstagramStoryResponse>> {
        if (hoursBack !in 1..8760) {
            throw ValidationException("Parametr hoursBack musi być z zakresu 1–8760 (max 1 rok).")
        }

        val principal = SecurityContextHelper.getCurrentUser()

        val parsedProfileId = profileId?.let {
            runCatching { UUID.fromString(it) }.getOrElse {
                throw ValidationException("Nieprawidłowy format profileId: $it")
            }
        }

        val stories = storiesHandler.handle(
            GetInstagramStoriesQuery(
                studioId = principal.studioId,
                profileId = parsedProfileId,
                hoursBack = hoursBack
            )
        )

        return ResponseEntity.ok(stories.map { dto ->
            InstagramStoryResponse(
                storyId = dto.storyId,
                imageUrl = dto.imageUrl,
                videoUrl = dto.videoUrl,
                takenAt = dto.takenAt,
                profileId = dto.profileId,
                username = dto.username
            )
        })
    }
}

data class InstagramStoryResponse(
    val storyId: String,
    val imageUrl: String?,
    val videoUrl: String?,
    val takenAt: Instant,
    val profileId: String,
    val username: String
)
