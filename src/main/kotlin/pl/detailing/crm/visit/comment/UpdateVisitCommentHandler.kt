package pl.detailing.crm.visit.comment

import org.apache.coyote.BadRequestException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.visit.domain.VisitCommentRevision
import pl.detailing.crm.visit.infrastructure.VisitCommentRepository
import pl.detailing.crm.visit.infrastructure.VisitCommentRevisionEntity
import pl.detailing.crm.visit.infrastructure.VisitCommentRevisionRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

@Service
class UpdateVisitCommentHandler(
    private val visitRepository: VisitRepository,
    private val visitCommentRepository: VisitCommentRepository,
    private val visitCommentRevisionRepository: VisitCommentRevisionRepository,
    private val userRepository: UserRepository
) {
    @Transactional
    suspend fun handle(command: UpdateVisitCommentCommand): UpdateVisitCommentResult {
        // Step 1: Get comment
        val commentEntity = visitCommentRepository.findById(command.commentId.value)
            .orElseThrow { NotFoundException("Comment not found: ${command.commentId}") }

        // Step 2: Verify visit exists and user has access
        val visitEntity = visitRepository.findById(commentEntity.visitId)
            .orElseThrow { NotFoundException("Visit not found") }

        if (visitEntity.studioId != command.studioId.value) {
            throw ForbiddenException("Visit does not belong to this studio")
        }

        // Step 3: Check if comment is deleted
        if (commentEntity.isDeleted) {
            throw BadRequestException("Cannot update deleted comment")
        }

        // Step 4: Check if content actually changed
        if (commentEntity.content == command.newContent) {
            return UpdateVisitCommentResult(
                commentId = command.commentId,
                wasChanged = false
            )
        }

        // Step 5: Get user details for audit
        val userEntity = userRepository.findById(command.userId.value)
            .orElseThrow { NotFoundException("User not found: ${command.userId}") }

        val userName = "${userEntity.firstName} ${userEntity.lastName}"

        // Step 6: Create revision entry
        val revision = VisitCommentRevision(
            id = VisitCommentRevisionId.random(),
            commentId = command.commentId,
            oldContent = commentEntity.content,
            newContent = command.newContent,
            changedBy = command.userId,
            changedByName = userName,
            changedAt = Instant.now()
        )

        val revisionEntity = VisitCommentRevisionEntity.fromDomain(revision)
        visitCommentRevisionRepository.save(revisionEntity)

        // Step 7: Update comment
        commentEntity.content = command.newContent
        commentEntity.updatedBy = command.userId.value
        commentEntity.updatedByName = userName
        commentEntity.updatedAt = Instant.now()
        visitCommentRepository.save(commentEntity)

        return UpdateVisitCommentResult(
            commentId = command.commentId,
            wasChanged = true
        )
    }
}

data class UpdateVisitCommentCommand(
    val studioId: StudioId,
    val userId: UserId,
    val commentId: VisitCommentId,
    val newContent: String
)

data class UpdateVisitCommentResult(
    val commentId: VisitCommentId,
    val wasChanged: Boolean
)
