package pl.detailing.crm.instagram

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.instagram.add.AddInstagramProfileCommand
import pl.detailing.crm.instagram.add.AddInstagramProfileHandler
import pl.detailing.crm.instagram.approve.ApproveInstagramProfileCommand
import pl.detailing.crm.instagram.approve.ApproveInstagramProfileHandler
import pl.detailing.crm.instagram.list.InstagramProfileDto
import pl.detailing.crm.instagram.list.ListInstagramProfilesHandler
import pl.detailing.crm.instagram.list.ListInstagramProfilesQuery
import pl.detailing.crm.instagram.posts.GetInstagramPostsHandler
import pl.detailing.crm.instagram.posts.GetInstagramPostsQuery
import pl.detailing.crm.instagram.posts.InstagramPostDto
import pl.detailing.crm.instagram.reject.RejectInstagramProfileCommand
import pl.detailing.crm.instagram.reject.RejectInstagramProfileHandler
import pl.detailing.crm.instagram.remove.RemoveInstagramProfileCommand
import pl.detailing.crm.instagram.remove.RemoveInstagramProfileHandler
import pl.detailing.crm.instagram.summary.GetCompetitionSummaryHandler
import pl.detailing.crm.instagram.summary.GetCompetitionSummaryQuery
import pl.detailing.crm.instagram.summary.InstagramProfileSummaryDto
import pl.detailing.crm.instagram.summary.WeeklyStatDto
import pl.detailing.crm.shared.*
import java.time.Instant

@RestController
@RequestMapping("/api/v1/instagram/profiles")
class InstagramController(
    private val addHandler: AddInstagramProfileHandler,
    private val approveHandler: ApproveInstagramProfileHandler,
    private val rejectHandler: RejectInstagramProfileHandler,
    private val removeHandler: RemoveInstagramProfileHandler,
    private val listHandler: ListInstagramProfilesHandler,
    private val postsHandler: GetInstagramPostsHandler,
    private val summaryHandler: GetCompetitionSummaryHandler
) {

    /**
     * Dodaj profil konkurencji do obserwowania.
     * Profil otrzymuje status PENDING_APPROVAL i czeka na akceptację admina.
     *
     * POST /api/v1/instagram/profiles
     */
    @PostMapping
    fun addProfile(
        @RequestBody request: AddInstagramProfileRequest
    ): ResponseEntity<InstagramProfileResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = AddInstagramProfileCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            username = request.username
        )

        val result = addHandler.handle(command)

        ResponseEntity.status(HttpStatus.CREATED).body(
            InstagramProfileResponse(
                id = result.studioProfileId.toString(),
                profileId = result.profileId.toString(),
                username = result.username,
                status = result.status.name,
                apiError = false,
                addedAt = Instant.now()
            )
        )
    }

    /**
     * Pobierz listę wszystkich profili obserwowanych przez studio.
     *
     * GET /api/v1/instagram/profiles
     */
    @GetMapping
    fun listProfiles(): ResponseEntity<List<InstagramProfileResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = listHandler.handle(ListInstagramProfilesQuery(principal.studioId))

        ResponseEntity.ok(result.map { it.toResponse() })
    }

    /**
     * Zatwierdź profil (tylko OWNER lub MANAGER).
     * Status zmienia się na ACTIVE – profil będzie uwzględniony w niedzielnym sync.
     *
     * POST /api/v1/instagram/profiles/{id}/approve
     */
    @PostMapping("/{id}/approve")
    fun approveProfile(@PathVariable id: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (!SecurityContextHelper.isManagerOrOwner()) {
            throw ForbiddenException("Tylko manager lub właściciel może zatwierdzać profile.")
        }

        approveHandler.handle(
            ApproveInstagramProfileCommand(
                studioId = principal.studioId,
                studioProfileId = StudioInstagramProfileId.fromString(id)
            )
        )

        ResponseEntity.noContent().build()
    }

    /**
     * Odrzuć profil (tylko OWNER lub MANAGER).
     * Status zmienia się na REJECTED – profil wypada z niedzielnego sync.
     *
     * POST /api/v1/instagram/profiles/{id}/reject
     */
    @PostMapping("/{id}/reject")
    fun rejectProfile(@PathVariable id: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (!SecurityContextHelper.isManagerOrOwner()) {
            throw ForbiddenException("Tylko manager lub właściciel może odrzucać profile.")
        }

        rejectHandler.handle(
            RejectInstagramProfileCommand(
                studioId = principal.studioId,
                studioProfileId = StudioInstagramProfileId.fromString(id)
            )
        )

        ResponseEntity.noContent().build()
    }

    /**
     * Usuń profil z listy obserwowanych przez studio.
     * Jeśli żadne inne studio go nie obserwuje, globalny profil jest usuwany automatycznie.
     *
     * DELETE /api/v1/instagram/profiles/{id}
     */
    @DeleteMapping("/{id}")
    fun removeProfile(@PathVariable id: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        removeHandler.handle(
            RemoveInstagramProfileCommand(
                studioId = principal.studioId,
                studioProfileId = StudioInstagramProfileId.fromString(id)
            )
        )

        ResponseEntity.noContent().build()
    }

    /**
     * Pobierz posty konkurenta (dane z ostatniej niedzielnej synchronizacji, nie z API).
     *
     * GET /api/v1/instagram/profiles/{id}/posts
     */
    @GetMapping("/{id}/posts")
    fun getPosts(@PathVariable id: String): ResponseEntity<List<InstagramPostResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val posts = postsHandler.handle(
            GetInstagramPostsQuery(
                studioId = principal.studioId,
                studioProfileId = StudioInstagramProfileId.fromString(id)
            )
        )

        ResponseEntity.ok(posts.map { it.toResponse() })
    }

    /**
     * Pobierz zagregowane statystyki wszystkich aktywnych profili konkurencji.
     * Zawiera tygodniowe statystyki (avgLikes, avgComments, postCount) oraz sumaryczne KPI.
     *
     * GET /api/v1/instagram/profiles/summary?weeks=N  (domyślnie 52)
     * Tylko profile o statusie ACTIVE są uwzględniane.
     */
    @GetMapping("/summary")
    fun getCompetitionSummary(
        @RequestParam(defaultValue = "52") weeks: Int
    ): ResponseEntity<List<InstagramProfileSummaryResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = summaryHandler.handle(
            GetCompetitionSummaryQuery(
                studioId = principal.studioId,
                weeks = weeks
            )
        )

        ResponseEntity.ok(result.map { it.toResponse() })
    }
}

// ---- Request / Response DTOs ----

data class AddInstagramProfileRequest(
    val username: String
)

data class InstagramProfileResponse(
    val id: String,
    val profileId: String,
    val username: String,
    val status: String,
    val apiError: Boolean,
    val addedAt: Instant
)

data class InstagramPostResponse(
    val id: String,
    val postPk: String,
    val postCode: String,
    val likeCount: Int,
    val commentCount: Int,
    val viewCount: Long?,
    val caption: String?,
    val takenAt: Instant,
    val scrapedAt: Instant,
    /** "feed_item" | "clips" | "carousel_container" – null dla starych rekordów */
    val productType: String?,
    /** Liczba slajdów karuzeli; dla innych typów 1 */
    val carouselMediaCount: Int,
    /** Hashtagi wyekstrahowane z caption */
    val hashtags: List<String>,
    /** Suma lajków i komentarzy */
    val engagementScore: Int
)

private fun InstagramProfileDto.toResponse() = InstagramProfileResponse(
    id = id,
    profileId = profileId,
    username = username,
    status = status.name,
    apiError = apiError,
    addedAt = addedAt
)

data class WeeklyStatResponse(
    val weekStart: String,
    val avgLikes: Double,
    val avgComments: Double,
    val postCount: Int
)

data class InstagramProfileSummaryResponse(
    val id: String,
    val profileId: String,
    val username: String,
    val status: String,
    val apiError: Boolean,
    val addedAt: Instant,
    val postCount: Int,
    val avgLikes: Double,
    val avgComments: Double,
    val avgViews: Double?,
    val postsPerWeek: Double,
    val lastPostAt: Instant?,
    val weeklyStats: List<WeeklyStatResponse>,
    /** Średnia (lajki + komentarze) na post – cała historia profilu, nie tylko okno `weeks` */
    val avgEngagement: Double
)

private fun InstagramPostDto.toResponse() = InstagramPostResponse(
    id = id,
    postPk = postPk,
    postCode = postCode,
    likeCount = likeCount,
    commentCount = commentCount,
    viewCount = viewCount,
    caption = caption,
    takenAt = takenAt,
    scrapedAt = scrapedAt,
    productType = productType,
    carouselMediaCount = carouselMediaCount,
    hashtags = hashtags,
    engagementScore = engagementScore
)

private fun WeeklyStatDto.toResponse() = WeeklyStatResponse(
    weekStart = weekStart,
    avgLikes = avgLikes,
    avgComments = avgComments,
    postCount = postCount
)

private fun InstagramProfileSummaryDto.toResponse() = InstagramProfileSummaryResponse(
    id = id,
    profileId = profileId,
    username = username,
    status = status.name,
    apiError = apiError,
    addedAt = addedAt,
    postCount = postCount,
    avgLikes = avgLikes,
    avgComments = avgComments,
    avgViews = avgViews,
    postsPerWeek = postsPerWeek,
    lastPostAt = lastPostAt,
    weeklyStats = weeklyStats.map { it.toResponse() },
    avgEngagement = avgEngagement
)
