package pl.detailing.crm.visit.comment

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.visit.infrastructure.VisitCommentRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

@Service
class DeleteVisitCommentHandler(
    private val visitRepository: VisitRepository,
    private val visitCommentRepository: VisitCommentRepository,
    private val userRepository: UserRepository
) {
    @Transactional
    suspend fun handle(command: DeleteVisitCommentCommand): DeleteVisitCommentResult {
        // Step 1: Get comment
        val commentEntity = visitCommentRepository.findById(command.commentId.value)
            .orElseThrow { NotFoundException("Comment not found: ${command.commentId}") }

        // Step 2: Verify visit exists and user has access
        val visitEntity = visitRepository.findById(commentEntity.visitId)
            .orElseThrow { NotFoundException("Visit not found") }

        if (visitEntity.studioId != command.studioId.value) {
            throw ForbiddenException("Visit does not belong to this studio")
        }

        // Step 3: Check if already deleted
        if (commentEntity.isDeleted) {
            return DeleteVisitCommentResult(
                commentId = command.commentId,
                wasDeleted = false
            )
        }

        // Step 4: Get user details for audit
        val userEntity = userRepository.findById(command.userId.value)
            .orElseThrow { NotFoundException("User not found: ${command.userId}") }

        val userName = "${userEntity.firstName} ${userEntity.lastName}"

        // Step 5: Soft delete comment
        commentEntity.isDeleted = true
        commentEntity.deletedBy = command.userId.value
        commentEntity.deletedByName = userName
        commentEntity.deletedAt = Instant.now()
        visitCommentRepository.save(commentEntity)

        return DeleteVisitCommentResult(
            commentId = command.commentId,
            wasDeleted = true
        )
    }
}

data class DeleteVisitCommentCommand(
    val studioId: StudioId,
    val userId: UserId,
    val commentId: VisitCommentId
)

data class DeleteVisitCommentResult(
    val commentId: VisitCommentId,
    val wasDeleted: Boolean
)
