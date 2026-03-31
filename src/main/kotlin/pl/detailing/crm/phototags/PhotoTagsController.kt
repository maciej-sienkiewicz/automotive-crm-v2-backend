package pl.detailing.crm.phototags

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper

/**
 * Endpoints for photo tag management.
 *
 * PUT /api/v1/photos/{photoId}/tags  — replace tags on a photo (visit or vehicle)
 * GET /api/v1/photo-tags/suggestions — return curated tag suggestions for the UI
 */
@RestController
class PhotoTagsController(
    private val updatePhotoTagsHandler: UpdatePhotoTagsHandler,
    private val getTagSuggestionsHandler: GetTagSuggestionsHandler
) {

    /**
     * Replace all tags on a photo.
     *
     * The photo can belong to a visit or a vehicle — the endpoint resolves the type
     * automatically using studio-scoped lookup, so the caller does not need to know
     * the photo source.
     *
     * PUT /api/v1/photos/{photoId}/tags
     * Body: { "tags": ["PPF", "przód", "po"] }
     * Response: { "tags": ["PPF", "przód", "po"] }
     */
    @PutMapping("/api/v1/photos/{photoId}/tags")
    fun updatePhotoTags(
        @PathVariable photoId: String,
        @RequestBody request: UpdatePhotoTagsRequest
    ): ResponseEntity<PhotoTagsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = UpdatePhotoTagsCommand.of(
            photoId = photoId,
            studioId = principal.studioId,
            tags = request.tags
        )

        val result = updatePhotoTagsHandler.handle(command)

        ResponseEntity.ok(PhotoTagsResponse(tags = result.tags))
    }

    /**
     * Returns a curated list of tag suggestions for the photo tagging UI.
     *
     * GET /api/v1/photo-tags/suggestions
     * Response: { "suggestions": ["przód", "tył", ...] }
     */
    @GetMapping("/api/v1/photo-tags/suggestions")
    fun getTagSuggestions(): ResponseEntity<TagSuggestionsResponse> {
        val result = getTagSuggestionsHandler.handle()
        return ResponseEntity.ok(TagSuggestionsResponse(suggestions = result.suggestions))
    }
}

data class UpdatePhotoTagsRequest(
    val tags: List<String>
)

data class PhotoTagsResponse(
    val tags: List<String>
)

data class TagSuggestionsResponse(
    val suggestions: List<String>
)
