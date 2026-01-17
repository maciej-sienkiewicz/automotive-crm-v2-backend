package pl.detailing.crm.visit.comment

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.domain.VisitComment
import pl.detailing.crm.visit.domain.VisitCommentRevision
import pl.detailing.crm.visit.infrastructure.VisitCommentRepository
import pl.detailing.crm.visit.infrastructure.VisitCommentRevisionRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository

@Service
class GetVisitCommentsHandler(
    private val visitRepository: VisitRepository,
    private val visitCommentRepository: VisitCommentRepository,
    private val visitCommentRevisionRepository: VisitCommentRevisionRepository
) {
    @Transactional(readOnly = true)
    suspend fun handle(query: GetVisitCommentsQuery): GetVisitCommentsResult {
        // Step 1: Verify visit exists and user has access
        val visitEntity = visitRepository.findById(query.visitId.value)
            .orElseThrow { NotFoundException("Visit not found: ${query.visitId}") }

        if (visitEntity.studioId != query.studioId.value) {
            throw ForbiddenException("Visit does not belong to this studio")
        }

        // Step 2: Get all comments (including deleted ones - they'll be shown as strikethrough)
        val commentEntities = visitCommentRepository.findByVisitIdOrderByCreatedAtAsc(query.visitId.value)
        val comments = commentEntities.map { it.toDomain() }

        // Step 3: Get all revisions for these comments
        val commentsWithRevisions = comments.map { comment ->
            val revisionEntities = visitCommentRevisionRepository
                .findByCommentIdOrderByChangedAtAsc(comment.id.value)
            val revisions = revisionEntities.map { it.toDomain() }

            CommentWithRevisions(
                comment = comment,
                revisions = revisions
            )
        }

        return GetVisitCommentsResult(
            visitId = query.visitId,
            comments = commentsWithRevisions
        )
    }
}

data class GetVisitCommentsQuery(
    val studioId: StudioId,
    val userId: UserId,
    val visitId: VisitId
)

data class GetVisitCommentsResult(
    val visitId: VisitId,
    val comments: List<CommentWithRevisions>
)

data class CommentWithRevisions(
    val comment: VisitComment,
    val revisions: List<VisitCommentRevision>
)
