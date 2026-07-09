package pl.detailing.crm.instagram

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.instagram.reaction.ReactToInstagramPostCommand
import pl.detailing.crm.instagram.reaction.ReactToInstagramPostHandler
import pl.detailing.crm.shared.InstagramPostReaction
import pl.detailing.crm.shared.InstagramPostSnapshotId
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.role.domain.Permission

@RequiresPermission(Permission.MARKETING_MANAGE)
@RestController
@RequestMapping("/api/v1/instagram/posts")
class InstagramPostController(
    private val reactHandler: ReactToInstagramPostHandler
) {

    /**
     * Ustaw lub usuń reakcję studia na post konkurenta.
     * Granulacja per-studioId – każde studio ocenia posty niezależnie.
     * Wartość null usuwa istniejącą reakcję.
     *
     * POST /api/v1/instagram/posts/{postId}/reaction
     * Body: { "reaction": "LIKED" | "DISLIKED" | null }
     */
    @PostMapping("/{postId}/reaction")
    fun reactToPost(
        @PathVariable postId: String,
        @RequestBody request: ReactToPostRequest
    ): ResponseEntity<ReactToPostResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val reaction = reactHandler.handle(
            ReactToInstagramPostCommand(
                studioId = principal.studioId,
                postId = InstagramPostSnapshotId.fromString(postId),
                reaction = request.reaction
            )
        )

        return ResponseEntity.ok(ReactToPostResponse(postId = postId, reaction = reaction))
    }
}

data class ReactToPostRequest(
    val reaction: InstagramPostReaction?
)

data class ReactToPostResponse(
    val postId: String,
    val reaction: InstagramPostReaction?
)
