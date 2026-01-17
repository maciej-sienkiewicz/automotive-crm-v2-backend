package pl.detailing.crm.visit.comment

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.domain.VisitComment
import pl.detailing.crm.visit.domain.VisitCommentRevision
import java.time.Instant

@RestController
@RequestMapping("/api/visits/{visitId}/comments")
class VisitCommentController(
    private val addCommentHandler: AddCommentToVisitHandler,
    private val updateCommentHandler: UpdateVisitCommentHandler,
    private val deleteCommentHandler: DeleteVisitCommentHandler,
    private val getCommentsHandler: GetVisitCommentsHandler
) {

    /**
     * Get all comments for a visit
     * GET /api/visits/{visitId}/comments
     */
    @GetMapping
    fun getVisitComments(
        @PathVariable visitId: String
    ): ResponseEntity<GetVisitCommentsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val query = GetVisitCommentsQuery(
            studioId = principal.studioId,
            userId = principal.userId,
            visitId = VisitId.fromString(visitId)
        )

        val result = getCommentsHandler.handle(query)

        ResponseEntity.ok(
            GetVisitCommentsResponse(
                comments = result.comments.map { commentWithRevisions ->
                    CommentDto.fromDomain(
                        comment = commentWithRevisions.comment,
                        revisions = commentWithRevisions.revisions
                    )
                }
            )
        )
    }

    /**
     * Add a comment to a visit
     * POST /api/visits/{visitId}/comments
     */
    @PostMapping
    fun addComment(
        @PathVariable visitId: String,
        @RequestBody request: AddCommentRequest
    ): ResponseEntity<AddCommentResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = AddCommentToVisitCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            visitId = VisitId.fromString(visitId),
            type = CommentType.valueOf(request.type),
            content = request.content
        )

        val result = addCommentHandler.handle(command)

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(AddCommentResponse(commentId = result.commentId.value.toString()))
    }

    /**
     * Update a comment
     * PUT /api/visits/{visitId}/comments/{commentId}
     */
    @PutMapping("/{commentId}")
    fun updateComment(
        @PathVariable visitId: String,
        @PathVariable commentId: String,
        @RequestBody request: UpdateCommentRequest
    ): ResponseEntity<UpdateCommentResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = UpdateVisitCommentCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            commentId = VisitCommentId.fromString(commentId),
            newContent = request.content
        )

        val result = updateCommentHandler.handle(command)

        ResponseEntity.ok(
            UpdateCommentResponse(
                commentId = result.commentId.value.toString(),
                wasChanged = result.wasChanged
            )
        )
    }

    /**
     * Delete a comment (soft delete)
     * DELETE /api/visits/{visitId}/comments/{commentId}
     */
    @DeleteMapping("/{commentId}")
    fun deleteComment(
        @PathVariable visitId: String,
        @PathVariable commentId: String
    ): ResponseEntity<DeleteCommentResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = DeleteVisitCommentCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            commentId = VisitCommentId.fromString(commentId)
        )

        val result = deleteCommentHandler.handle(command)

        ResponseEntity.ok(
            DeleteCommentResponse(
                commentId = result.commentId.value.toString(),
                wasDeleted = result.wasDeleted
            )
        )
    }
}

// DTOs
data class AddCommentRequest(
    val type: String,  // INTERNAL or FOR_CUSTOMER
    val content: String
)

data class AddCommentResponse(
    val commentId: String
)

data class UpdateCommentRequest(
    val content: String
)

data class UpdateCommentResponse(
    val commentId: String,
    val wasChanged: Boolean
)

data class DeleteCommentResponse(
    val commentId: String,
    val wasDeleted: Boolean
)

data class GetVisitCommentsResponse(
    val comments: List<CommentDto>
)

data class CommentDto(
    val id: String,
    val visitId: String,
    val type: String,
    val content: String,
    val isDeleted: Boolean,
    val createdBy: String,
    val createdByName: String,
    val createdAt: String,
    val updatedBy: String?,
    val updatedByName: String?,
    val updatedAt: String?,
    val deletedBy: String?,
    val deletedByName: String?,
    val deletedAt: String?,
    val revisions: List<RevisionDto>
) {
    companion object {
        fun fromDomain(comment: VisitComment, revisions: List<VisitCommentRevision>): CommentDto =
            CommentDto(
                id = comment.id.value.toString(),
                visitId = comment.visitId.value.toString(),
                type = comment.type.name,
                content = comment.content,
                isDeleted = comment.isDeleted,
                createdBy = comment.createdBy.value.toString(),
                createdByName = comment.createdByName,
                createdAt = comment.createdAt.toString(),
                updatedBy = comment.updatedBy?.value?.toString(),
                updatedByName = comment.updatedByName,
                updatedAt = comment.updatedAt?.toString(),
                deletedBy = comment.deletedBy?.value?.toString(),
                deletedByName = comment.deletedByName,
                deletedAt = comment.deletedAt?.toString(),
                revisions = revisions.map { RevisionDto.fromDomain(it) }
            )
    }
}

data class RevisionDto(
    val id: String,
    val commentId: String,
    val oldContent: String,
    val newContent: String,
    val changedBy: String,
    val changedByName: String,
    val changedAt: String
) {
    companion object {
        fun fromDomain(revision: VisitCommentRevision): RevisionDto =
            RevisionDto(
                id = revision.id.value.toString(),
                commentId = revision.commentId.value.toString(),
                oldContent = revision.oldContent,
                newContent = revision.newContent,
                changedBy = revision.changedBy.value.toString(),
                changedByName = revision.changedByName,
                changedAt = revision.changedAt.toString()
            )
    }
}
