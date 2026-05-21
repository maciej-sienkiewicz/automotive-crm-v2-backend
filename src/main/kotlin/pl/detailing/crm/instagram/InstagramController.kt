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
import pl.detailing.crm.instagram.summary.FollowerSnapshotDto
import pl.detailing.crm.instagram.summary.GetCompetitionSummaryHandler
import pl.detailing.crm.instagram.summary.GetCompetitionSummaryQuery
import pl.detailing.crm.instagram.summary.InstagramProfileSummaryDto
import pl.detailing.crm.instagram.summary.WeeklyStatDto
import pl.detailing.crm.instagram.sync.InstagramSyncService
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
    private val summaryHandler: GetCompetitionSummaryHandler,
    private val sync: InstagramSyncService,
) {

    @PostMapping
    fun addProfile(
        @RequestBody request: AddInstagramProfileRequest
    ): ResponseEntity<InstagramProfileResponse> = runBlocking {
        sync.syncAllActiveProfiles()
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

    @GetMapping
    fun listProfiles(): ResponseEntity<List<InstagramProfileResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = listHandler.handle(ListInstagramProfilesQuery(principal.studioId))

        ResponseEntity.ok(result.map { it.toResponse() })
    }

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
     * Pobierz zagregowane statystyki aktywnych profili konkurencji.
     *
     * GET /api/v1/instagram/profiles/summary?weeks=N  (domyślnie 52)
     *
     * Odpowiedź zawiera:
     * - Statystyki postów (avgLikes, avgComments, postsPerWeek, weeklyStats z postCount i storyCount)
     * - Aktywność stories (storiesPerWeek, storyCount per tydzień w weeklyStats)
     * - Metryki profilu (followerCount, hasContactData, isVerified, category, externalUrl itp.)
     * - Historię followerów (followerHistory) do analizy trendu w oknie `weeks`
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
    val productType: String?,
    val carouselMediaCount: Int,
    val hashtags: List<String>,
    val engagementScore: Int,
    val imageUrl: String?
)

data class WeeklyStatResponse(
    val weekStart: String,
    val avgLikes: Double,
    val avgComments: Double,
    val postCount: Int,
    /** Liczba stories opublikowanych w tym tygodniu. */
    val storyCount: Int
)

data class FollowerSnapshotResponse(
    val date: String,
    val followerCount: Int?
)

data class InstagramProfileSummaryResponse(
    val id: String,
    val profileId: String,
    val username: String,
    val status: String,
    val apiError: Boolean,
    val addedAt: Instant,
    // ── Metryki postów ──
    val postCount: Int,
    val avgLikes: Double,
    val avgComments: Double,
    val avgViews: Double?,
    val postsPerWeek: Double,
    val lastPostAt: Instant?,
    val weeklyStats: List<WeeklyStatResponse>,
    val avgEngagement: Double,
    // ── Aktywność stories ──
    val storiesPerWeek: Double,
    // ── Metryki profilu ──
    val followerCount: Int?,
    val followingCount: Int?,
    val mediaCount: Int?,
    /** true gdy profil ma uzupełniony publiczny e-mail lub numer telefonu. */
    val hasContactData: Boolean,
    val isVerified: Boolean,
    val isBusiness: Boolean,
    /** 1 = personal, 2 = creator, 3 = professional/business */
    val accountType: Int?,
    val category: String?,
    val externalUrl: String?,
    val biography: String?,
    val hasHighlightReels: Boolean,
    val totalClipsCount: Int,
    val isPrivate: Boolean,
    val detailsLastSyncedAt: Instant?,
    // ── Trend followerów ──
    val followerHistory: List<FollowerSnapshotResponse>
)

private fun InstagramProfileDto.toResponse() = InstagramProfileResponse(
    id = id,
    profileId = profileId,
    username = username,
    status = status.name,
    apiError = apiError,
    addedAt = addedAt
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
    engagementScore = engagementScore,
    imageUrl = imageUrl
)

private fun WeeklyStatDto.toResponse() = WeeklyStatResponse(
    weekStart = weekStart,
    avgLikes = avgLikes,
    avgComments = avgComments,
    postCount = postCount,
    storyCount = storyCount
)

private fun FollowerSnapshotDto.toResponse() = FollowerSnapshotResponse(
    date = date,
    followerCount = followerCount
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
    avgEngagement = avgEngagement,
    storiesPerWeek = storiesPerWeek,
    followerCount = followerCount,
    followingCount = followingCount,
    mediaCount = mediaCount,
    hasContactData = hasContactData,
    isVerified = isVerified,
    isBusiness = isBusiness,
    accountType = accountType,
    category = category,
    externalUrl = externalUrl,
    biography = biography,
    hasHighlightReels = hasHighlightReels,
    totalClipsCount = totalClipsCount,
    isPrivate = isPrivate,
    detailsLastSyncedAt = detailsLastSyncedAt,
    followerHistory = followerHistory.map { it.toResponse() }
)
