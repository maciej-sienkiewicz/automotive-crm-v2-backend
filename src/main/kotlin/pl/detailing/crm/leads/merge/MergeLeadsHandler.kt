package pl.detailing.crm.leads.merge

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
import pl.detailing.crm.leads.comments.AddLeadCommentCommand
import pl.detailing.crm.leads.comments.LeadCommentHandler
import pl.detailing.crm.leads.comments.LeadCommentRepository
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.lostreason.UpdateLostReasonCommand
import pl.detailing.crm.leads.lostreason.UpdateLostReasonHandler
import pl.detailing.crm.leads.update.UpdateLeadCommand
import pl.detailing.crm.leads.update.UpdateLeadHandler
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant

/**
 * Merge two leads of the same client into one.
 *
 * The [source][MergeLeadsCommand.sourceLeadId] lead is folded into the
 * [target][MergeLeadsCommand.targetLeadId]: all its comments move to the target, a summary note is
 * left on the target, and the source is closed as LOST with a "merged" reason (so the action is
 * preserved in the lead's status history instead of deleting data). Used to clean up true duplicates.
 */
data class MergeLeadsCommand(
    val sourceLeadId: LeadId,
    val targetLeadId: LeadId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String
)

data class MergeLeadsResult(
    val targetLeadId: LeadId
)

@Service
class MergeLeadsHandler(
    private val leadRepository: LeadRepository,
    private val commentRepository: LeadCommentRepository,
    private val leadCommentHandler: LeadCommentHandler,
    private val updateLeadHandler: UpdateLeadHandler,
    private val updateLostReasonHandler: UpdateLostReasonHandler,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(command: MergeLeadsCommand): MergeLeadsResult =
        withContext(Dispatchers.IO) {
            if (command.sourceLeadId == command.targetLeadId) {
                throw ValidationException("Nie można scalić leada z samym sobą")
            }

            val source = leadRepository.findById(command.sourceLeadId.value)
                .orElseThrow { EntityNotFoundException("Lead źródłowy nie został znaleziony: ${command.sourceLeadId}") }
            if (source.studioId != command.studioId.value) {
                throw ForbiddenException("Lead nie należy do tego studia")
            }

            val target = leadRepository.findById(command.targetLeadId.value)
                .orElseThrow { EntityNotFoundException("Lead docelowy nie został znaleziony: ${command.targetLeadId}") }
            if (target.studioId != command.studioId.value) {
                throw ForbiddenException("Lead nie należy do tego studia")
            }

            // Only merge leads of the same client to avoid mixing unrelated contacts.
            if (source.contactIdentifier != target.contactIdentifier) {
                throw ValidationException("Można scalać tylko leady tego samego klienta (ten sam kontakt)")
            }

            // Move all active comments from source to target, preserving authorship and timestamps.
            val movedComments = commentRepository.reassignComments(source.id, target.id)

            // Leave a summary note on the target so the merged context is visible in the timeline.
            val annotation = buildString {
                append("Scalono leada utworzonego ${source.createdAt}.")
                source.initialMessage?.trim()?.takeIf { it.isNotBlank() }?.let {
                    append("\n\nPierwotna wiadomość:\n")
                    append(it)
                }
            }
            leadCommentHandler.addComment(
                AddLeadCommentCommand(
                    leadId = command.targetLeadId,
                    studioId = command.studioId,
                    userId = command.userId,
                    userName = command.userName,
                    content = annotation
                )
            )

            target.newActivityAt = Instant.now()
            target.updatedAt = Instant.now()
            leadRepository.save(target)

            // Close the source as LOST, reusing the existing flow so the change lands in status history.
            updateLeadHandler.handle(
                UpdateLeadCommand(
                    leadId = command.sourceLeadId,
                    studioId = command.studioId,
                    userId = command.userId,
                    status = LeadStatus.LOST,
                    customerName = null,
                    initialMessage = null,
                    estimatedValue = null,
                    userName = command.userName
                )
            )
            updateLostReasonHandler.handle(
                UpdateLostReasonCommand(
                    leadId = command.sourceLeadId,
                    studioId = command.studioId,
                    requestingUserId = command.userId,
                    requestingUserName = command.userName,
                    lostReason = "Scalony z leadem ${command.targetLeadId.value}"
                )
            )

            log.info(
                "[LEADS] Merged lead: sourceLeadId={}, targetLeadId={}, movedComments={}",
                command.sourceLeadId.value, command.targetLeadId.value, movedComments
            )

            auditService.log(
                LogAuditCommand(
                    studioId = command.studioId,
                    userId = command.userId,
                    userDisplayName = command.userName,
                    module = AuditModule.LEAD,
                    entityId = command.targetLeadId.value.toString(),
                    entityDisplayName = target.customerName ?: target.contactIdentifier,
                    action = AuditAction.LEAD_MERGED,
                    changes = listOf(
                        FieldChange("mergedFromLeadId", command.sourceLeadId.value.toString(), null)
                    )
                )
            )

            MergeLeadsResult(targetLeadId = command.targetLeadId)
        }
}
