package pl.detailing.crm.leads.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.leads.domain.Lead
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.*
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
                .orElseThrow { EntityNotFoundException("Lead not found: ${command.leadId}") }

            // Verify studio ownership
            if (entity.studioId != command.studioId.value) {
                throw ForbiddenException("Lead does not belong to this studio")
            }

            // Validate estimated value if provided
            if (command.estimatedValue != null && command.estimatedValue < 0) {
                throw ValidationException("Estimated value cannot be negative")
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
                // Clear verification flag when status changes to IN_PROGRESS
                if (it == LeadStatus.IN_PROGRESS) {
                    entity.requiresVerification = false
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
            val newStatus = updated.status
            val auditAction = when {
                command.status == LeadStatus.CONVERTED && oldStatus != LeadStatus.CONVERTED -> AuditAction.LEAD_CONVERTED
                command.status == LeadStatus.ABANDONED && oldStatus != LeadStatus.ABANDONED -> AuditAction.LEAD_ABANDONED
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
