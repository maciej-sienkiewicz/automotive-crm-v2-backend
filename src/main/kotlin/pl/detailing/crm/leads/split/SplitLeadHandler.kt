package pl.detailing.crm.leads.split

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
import pl.detailing.crm.leads.comments.LeadCommentRepository
import pl.detailing.crm.leads.create.CreateLeadCommand
import pl.detailing.crm.leads.create.CreateLeadHandler
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.util.UUID

/**
 * Split a comment out of an existing lead into its own, independent lead.
 *
 * Used to repair historically conflated leads — e.g. a client asked about PPF and later about a
 * ceramic coating, and the second inquiry was appended as a comment instead of becoming a separate
 * sales opportunity. The selected comment becomes the [initial message][CreateLeadCommand.initialMessage]
 * of a brand-new lead that inherits the source lead's contact, customer link and source channel.
 */
data class SplitLeadCommand(
    val sourceLeadId: LeadId,
    val commentId: UUID,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String
)

data class SplitLeadResult(
    val newLeadId: LeadId,
    val sourceLeadId: LeadId
)

@Service
class SplitLeadHandler(
    private val leadRepository: LeadRepository,
    private val commentRepository: LeadCommentRepository,
    private val createLeadHandler: CreateLeadHandler,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(command: SplitLeadCommand): SplitLeadResult =
        withContext(Dispatchers.IO) {
            val sourceLead = leadRepository.findById(command.sourceLeadId.value)
                .orElseThrow { EntityNotFoundException("Lead nie został znaleziony: ${command.sourceLeadId}") }
            if (sourceLead.studioId != command.studioId.value) {
                throw ForbiddenException("Lead nie należy do tego studia")
            }

            val comment = commentRepository.findActiveByIdAndStudioId(command.commentId, command.studioId.value)
                ?: throw EntityNotFoundException("Komentarz nie został znaleziony")
            if (comment.leadId != command.sourceLeadId.value) {
                throw ForbiddenException("Komentarz nie należy do tego leada")
            }

            // Create a new lead seeded with the comment content. Reuse CreateLeadHandler so the new
            // lead goes through the same validation, auto-assignment and event publishing as any other.
            val created = createLeadHandler.handle(
                CreateLeadCommand(
                    studioId = command.studioId,
                    userId = command.userId,
                    source = sourceLead.source,
                    contactIdentifier = sourceLead.contactIdentifier,
                    customerName = sourceLead.customerName,
                    initialMessage = comment.content,
                    estimatedValue = 0L,
                    userName = command.userName,
                    initialStatus = LeadStatus.NEW,
                    customerId = sourceLead.customerId?.let { CustomerId(it) }
                )
            )

            // Move the comment to the new lead, preserving its author and timestamps.
            commentRepository.reassignComment(command.commentId, created.leadId.value)

            log.info(
                "[LEADS] Split comment into new lead: sourceLeadId={}, commentId={}, newLeadId={}",
                command.sourceLeadId.value, command.commentId, created.leadId.value
            )

            auditService.log(
                LogAuditCommand(
                    studioId = command.studioId,
                    userId = command.userId,
                    userDisplayName = command.userName,
                    module = AuditModule.LEAD,
                    entityId = command.sourceLeadId.value.toString(),
                    entityDisplayName = sourceLead.customerName ?: sourceLead.contactIdentifier,
                    action = AuditAction.LEAD_SPLIT,
                    changes = listOf(
                        FieldChange("splitCommentId", command.commentId.toString(), null),
                        FieldChange("newLeadId", null, created.leadId.value.toString())
                    )
                )
            )

            SplitLeadResult(
                newLeadId = created.leadId,
                sourceLeadId = command.sourceLeadId
            )
        }
}
