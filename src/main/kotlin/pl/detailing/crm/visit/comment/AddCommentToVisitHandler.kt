package pl.detailing.crm.visit.comment

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.visit.domain.VisitComment
import pl.detailing.crm.visit.infrastructure.VisitCommentEntity
import pl.detailing.crm.visit.infrastructure.VisitCommentRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

@Service
class AddCommentToVisitHandler(
    private val visitRepository: VisitRepository,
    private val visitCommentRepository: VisitCommentRepository,
    private val userRepository: UserRepository
) {
    @Transactional
    suspend fun handle(command: AddCommentToVisitCommand): AddCommentToVisitResult {
        // Step 1: Verify visit exists and user has access
        val visitEntity = visitRepository.findById(command.visitId.value)
            .orElseThrow { NotFoundException("Visit not found: ${command.visitId}") }

        if (visitEntity.studioId != command.studioId.value) {
            throw ForbiddenException("Visit does not belong to this studio")
        }

        // Step 2: Get user details for audit
        val userEntity = userRepository.findById(command.userId.value)
            .orElseThrow { NotFoundException("User not found: ${command.userId}") }

        val userName = "${userEntity.firstName} ${userEntity.lastName}"

        // Step 3: Create comment domain object
        val comment = VisitComment(
            id = VisitCommentId.random(),
            visitId = command.visitId,
            type = command.type,
            content = command.content,
            isDeleted = false,
            createdBy = command.userId,
            createdByName = userName,
            createdAt = Instant.now(),
            updatedBy = null,
            updatedByName = null,
            updatedAt = null,
            deletedBy = null,
            deletedByName = null,
            deletedAt = null
        )

        // Step 4: Save to database
        val commentEntity = VisitCommentEntity.fromDomain(comment)
        visitCommentRepository.save(commentEntity)

        return AddCommentToVisitResult(
            commentId = comment.id
        )
    }
}

data class AddCommentToVisitCommand(
    val studioId: StudioId,
    val userId: UserId,
    val visitId: VisitId,
    val type: CommentType,
    val content: String
)

data class AddCommentToVisitResult(
    val commentId: VisitCommentId
)
