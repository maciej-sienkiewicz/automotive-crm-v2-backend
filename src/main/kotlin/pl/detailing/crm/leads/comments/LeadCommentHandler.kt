package pl.detailing.crm.leads.comments

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

data class AddLeadCommentCommand(
    val leadId: LeadId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String,
    val content: String
)

data class UpdateLeadCommentCommand(
    val commentId: UUID,
    val leadId: LeadId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String,
    val content: String
)

data class DeleteLeadCommentCommand(
    val commentId: UUID,
    val leadId: LeadId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String
)

@Service
class LeadCommentHandler(
    private val leadRepository: LeadRepository,
    private val commentRepository: LeadCommentRepository,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(LeadCommentHandler::class.java)

    @Transactional(readOnly = true)
    suspend fun listComments(leadId: LeadId, studioId: StudioId): List<LeadCommentEntity> =
        withContext(Dispatchers.IO) {
            verifyLeadAccess(leadId, studioId)
            commentRepository.findActiveByLeadId(leadId.value)
        }

    @Transactional
    suspend fun addComment(command: AddLeadCommentCommand): LeadCommentEntity =
        withContext(Dispatchers.IO) {
            verifyLeadAccess(command.leadId, command.studioId)

            val content = command.content.trim()
            require(content.isNotBlank()) { "Treść komentarza nie może być pusta" }
            require(content.length <= 5000) { "Komentarz nie może przekraczać 5000 znaków" }

            val entity = LeadCommentEntity(
                id = UUID.randomUUID(),
                leadId = command.leadId.value,
                studioId = command.studioId.value,
                content = content,
                createdBy = command.userId.value,
                createdByName = command.userName,
                createdAt = Instant.now(),
                updatedBy = null,
                updatedByName = null,
                updatedAt = null,
                deletedBy = null,
                deletedByName = null,
                deletedAt = null
            )

            val saved = commentRepository.save(entity)
            log.info("[LEADS] Added comment: commentId={}, leadId={}", saved.id, command.leadId.value)

            auditService.log(LogAuditCommand(
                studioId = command.studioId,
                userId = command.userId,
                userDisplayName = command.userName,
                module = AuditModule.LEAD,
                entityId = command.leadId.value.toString(),
                entityDisplayName = null,
                action = AuditAction.LEAD_COMMENT_ADDED,
                changes = emptyList()
            ))

            saved
        }

    @Transactional
    suspend fun updateComment(command: UpdateLeadCommentCommand): LeadCommentEntity =
        withContext(Dispatchers.IO) {
            val entity = commentRepository.findActiveByIdAndStudioId(command.commentId, command.studioId.value)
                ?: throw EntityNotFoundException("Komentarz nie został znaleziony")

            if (entity.leadId != command.leadId.value) {
                throw ForbiddenException("Komentarz nie należy do tego leada")
            }

            val content = command.content.trim()
            require(content.isNotBlank()) { "Treść komentarza nie może być pusta" }
            require(content.length <= 5000) { "Komentarz nie może przekraczać 5000 znaków" }

            val oldContent = entity.content
            entity.content = content
            entity.updatedBy = command.userId.value
            entity.updatedByName = command.userName
            entity.updatedAt = Instant.now()

            val saved = commentRepository.save(entity)
            log.info("[LEADS] Updated comment: commentId={}", command.commentId)

            auditService.log(LogAuditCommand(
                studioId = command.studioId,
                userId = command.userId,
                userDisplayName = command.userName,
                module = AuditModule.LEAD,
                entityId = command.leadId.value.toString(),
                entityDisplayName = null,
                action = AuditAction.LEAD_COMMENT_UPDATED,
                changes = listOf(FieldChange("content", oldContent, content))
            ))

            saved
        }

    @Transactional
    suspend fun deleteComment(command: DeleteLeadCommentCommand) =
        withContext(Dispatchers.IO) {
            val entity = commentRepository.findActiveByIdAndStudioId(command.commentId, command.studioId.value)
                ?: throw EntityNotFoundException("Komentarz nie został znaleziony")

            if (entity.leadId != command.leadId.value) {
                throw ForbiddenException("Komentarz nie należy do tego leada")
            }

            entity.deletedBy = command.userId.value
            entity.deletedByName = command.userName
            entity.deletedAt = Instant.now()

            commentRepository.save(entity)
            log.info("[LEADS] Deleted comment: commentId={}", command.commentId)

            auditService.log(LogAuditCommand(
                studioId = command.studioId,
                userId = command.userId,
                userDisplayName = command.userName,
                module = AuditModule.LEAD,
                entityId = command.leadId.value.toString(),
                entityDisplayName = null,
                action = AuditAction.LEAD_COMMENT_DELETED,
                changes = emptyList()
            ))
        }

    private fun verifyLeadAccess(leadId: LeadId, studioId: StudioId) {
        val lead = leadRepository.findById(leadId.value)
            .orElseThrow { EntityNotFoundException("Lead nie został znaleziony: $leadId") }
        if (lead.studioId != studioId.value) {
            throw ForbiddenException("Lead nie należy do tego studia")
        }
    }
}
