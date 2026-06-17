package pl.detailing.crm.leads.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.leads.domain.Lead
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant

@Service
class UpdateLeadHandler(
    private val leadRepository: LeadRepository,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(UpdateLeadHandler::class.java)

    @Transactional
    suspend fun handle(command: UpdateLeadCommand): UpdateLeadResult =
        withContext(Dispatchers.IO) {
            // Find lead
            val entity = leadRepository.findById(command.leadId.value)
                .orElseThrow { EntityNotFoundException("Lead nie został znaleziony: ${command.leadId}") }

            // Verify studio ownership
            if (entity.studioId != command.studioId.value) {
                throw ForbiddenException("Lead nie należy do tego studia")
            }

            // Validate estimated value if provided
            if (command.estimatedValue != null && command.estimatedValue < 0) {
                throw ValidationException("Szacowana wartość nie może być ujemna")
            }

            // Capture old values for audit
            val oldStatus = entity.status
            val oldValues = mapOf(
                "status" to entity.status.name,
                "customerName" to entity.customerName,
                "initialMessage" to entity.initialMessage,
                "estimatedValue" to entity.estimatedValue.toString()
            )

            // Update fields
            command.status?.let {
                entity.status = it
                // Clear verification flag when an agent picks up the lead
                if (it == LeadStatus.IN_PROGRESS || it == LeadStatus.CONFIRMED) {
                    entity.requiresVerification = false
                }
                // Auto-assign to the first person who moves lead out of NEW
                if (oldStatus == LeadStatus.NEW && it != LeadStatus.NEW && entity.assignedUserId == null) {
                    entity.assignedUserId = command.userId?.value
                    entity.assignedUserName = command.userName
                }
            }
            command.customerName?.let { entity.customerName = it.trim() }
            command.initialMessage?.let { entity.initialMessage = it.trim() }
            command.estimatedValue?.let { entity.estimatedValue = it }
            entity.updatedAt = Instant.now()

            // Save
            val updated = leadRepository.save(entity)

            log.info("[LEADS] Updated lead: leadId={}, studioId={}, status={}",
                updated.id, updated.studioId, updated.status)

            // Determine audit action based on status change
            val auditAction = when {
                command.status == LeadStatus.CONFIRMED && oldStatus != LeadStatus.CONFIRMED -> AuditAction.LEAD_CONFIRMED
                command.status == LeadStatus.COMPLETED && oldStatus != LeadStatus.COMPLETED -> AuditAction.LEAD_COMPLETED
                command.status == LeadStatus.LOST && oldStatus != LeadStatus.LOST -> AuditAction.LEAD_LOST
                command.status == LeadStatus.NO_SHOW && oldStatus != LeadStatus.NO_SHOW -> AuditAction.LEAD_NO_SHOW
                command.status != null && command.status != oldStatus -> AuditAction.STATUS_CHANGE
                else -> AuditAction.UPDATE
            }

            val newValues = mapOf(
                "status" to updated.status.name,
                "customerName" to updated.customerName,
                "initialMessage" to updated.initialMessage,
                "estimatedValue" to updated.estimatedValue.toString()
            )
            val changes = auditService.computeChanges(oldValues, newValues)

            if (changes.isNotEmpty()) {
                auditService.log(LogAuditCommand(
                    studioId = command.studioId,
                    userId = command.userId ?: UserId(java.util.UUID.randomUUID()),
                    userDisplayName = command.userName ?: "",
                    module = AuditModule.LEAD,
                    entityId = command.leadId.value.toString(),
                    entityDisplayName = updated.customerName,
                    action = auditAction,
                    changes = changes
                ))
            }

            UpdateLeadResult(
                leadId = LeadId(updated.id),
                status = updated.status,
                customerName = updated.customerName,
                initialMessage = updated.initialMessage,
                estimatedValue = updated.estimatedValue,
                requiresVerification = updated.requiresVerification,
                updatedAt = updated.updatedAt
            )
        }
}

data class UpdateLeadResult(
    val leadId: LeadId,
    val status: LeadStatus,
    val customerName: String?,
    val initialMessage: String?,
    val estimatedValue: Long,
    val requiresVerification: Boolean,
    val updatedAt: Instant
)
