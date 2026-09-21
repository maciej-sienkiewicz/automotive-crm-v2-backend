package pl.detailing.crm.visit.comment

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.livemetrics.BusinessEventPublisher
import pl.detailing.crm.livemetrics.domain.BusinessEventType
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
    private val userRepository: UserRepository,
    private val auditService: AuditService,
    private val businessEventPublisher: BusinessEventPublisher
) {
    @Transactional
    suspend fun handle(command: AddCommentToVisitCommand): AddCommentToVisitResult {
        // Step 1: Verify visit exists and user has access
        val visitEntity = visitRepository.findById(command.visitId.value)
            .orElseThrow { NotFoundException("Wizyta nie została znaleziona: ${command.visitId}") }

        if (visitEntity.studioId != command.studioId.value) {
            throw ForbiddenException("Wizyta nie należy do tego studia")
        }

        // Step 2: Get user details for audit
        val userEntity = userRepository.findById(command.userId.value)
            .orElseThrow { NotFoundException("Użytkownik nie został znaleziony: ${command.userId}") }

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

        // Live metrics — liczymy komentarz dodany do wizyty, z podziałem na wewnętrzny i dla klienta.
        businessEventPublisher.publish(
            tenantId = command.studioId,
            type = BusinessEventType.VISIT_COMMENT_ADDED,
            dimensionValue = command.type.name,
            attributes = mapOf(
                "commentId" to comment.id.value.toString(),
                "visitId" to command.visitId.value.toString(),
                "userId" to command.userId.value.toString()
            )
        )

        // Step 5: Audit log
        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = userName,
            module = AuditModule.VISIT,
            entityId = command.visitId.value.toString(),
            entityDisplayName = "Wizyta #${visitEntity.visitNumber}",
            action = AuditAction.COMMENT_ADDED,
            metadata = mapOf(
                "commentId" to comment.id.value.toString(),
                "commentType" to command.type.name
            )
        ))

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
