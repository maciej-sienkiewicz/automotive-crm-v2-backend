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
import pl.detailing.crm.instagram.self.MarkSelfProfileCommand
import pl.detailing.crm.instagram.self.MarkSelfProfileHandler
import pl.detailing.crm.instagram.sync.InstagramResyncService
import pl.detailing.crm.instagram.sync.ResyncCooldownException
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.*
import java.time.Instant
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.RequiresCapability

@RequiresPermission(Permission.MARKETING_MANAGE)
@RequiresCapability(CapabilityKey.INSTAGRAM_MONITOR)
@RestController
@RequestMapping("/api/v1/instagram/profiles")
class InstagramController(
    private val addHandler: AddInstagramProfileHandler,
    private val approveHandler: ApproveInstagramProfileHandler,
    private val rejectHandler: RejectInstagramProfileHandler,
    private val removeHandler: RemoveInstagramProfileHandler,
    private val listHandler: ListInstagramProfilesHandler,
    private val postsHandler: GetInstagramPostsHandler,
    private val markSelfHandler: MarkSelfProfileHandler,
    private val resyncService: InstagramResyncService
) {

    /**
     * Ręczne ponowienie pobrania danych dla profili z etykietą "problem z pobraniem".
     *
     * Dotyka wyłącznie profili z ustawioną flagą błędu, więc kliknięcie przy zdrowych
     * danych nie kosztuje ani jednego wywołania API. Cooldown per studio chroni dzienny
     * budżet RapidAPI przed wielokrotnym klikaniem.
     */
    @PostMapping("/resync-failed")
    fun resyncFailed(): ResponseEntity<Any> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        try {
            ResponseEntity.ok(resyncService.resyncFailed(principal.studioId))
        } catch (e: ResyncCooldownException) {
            val minutes = (e.retryAfterSeconds + 59) / 60
            ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", e.retryAfterSeconds.toString())
                .body(
                    mapOf(
                        "message" to "Ponowienie było już uruchamiane przed chwilą. Spróbuj ponownie za ok. $minutes min.",
                        "retryAfterSeconds" to e.retryAfterSeconds
                    )
                )
        }
    }

    @PostMapping
    fun addProfile(
        @RequestBody request: AddInstagramProfileRequest
    ): ResponseEntity<InstagramProfileResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = addHandler.handle(
            AddInstagramProfileCommand(
                studioId = principal.studioId,
                userId = principal.userId,
                username = request.username
            )
        )

        ResponseEntity.status(HttpStatus.CREATED).body(
            InstagramProfileResponse(
                id = result.studioProfileId.toString(),
                profileId = result.profileId.toString(),
                username = result.username,
                status = result.status.name,
                apiError = false,
                isSelf = false,
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

    /**
     * Oznacza profil jako "Twoje studio" (punkt odniesienia benchmarku)
     * lub zdejmuje oznaczenie. Maksymalnie jeden własny profil per studio.
     */
    @PostMapping("/{id}/mark-self")
    fun markSelf(
        @PathVariable id: String,
        @RequestBody request: MarkSelfRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        markSelfHandler.handle(
            MarkSelfProfileCommand(
                studioId = principal.studioId,
                studioProfileId = StudioInstagramProfileId.fromString(id),
                isSelf = request.isSelf
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
}

// ---- Request / Response DTOs ----

data class AddInstagramProfileRequest(
    val username: String
)

data class MarkSelfRequest(
    val isSelf: Boolean
)

data class InstagramProfileResponse(
    val id: String,
    val profileId: String,
    val username: String,
    val status: String,
    val apiError: Boolean,
    val isSelf: Boolean,
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
    val permalink: String
)

private fun InstagramProfileDto.toResponse() = InstagramProfileResponse(
    id = id,
    profileId = profileId,
    username = username,
    status = status.name,
    apiError = apiError,
    isSelf = isSelf,
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
    permalink = permalink
)
