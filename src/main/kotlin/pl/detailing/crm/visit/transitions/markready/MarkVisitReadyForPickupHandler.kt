package pl.detailing.crm.visit.transitions.markready

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.email.visitready.SendVisitReadyForPickupEmailCommand
import pl.detailing.crm.email.visitready.SendVisitReadyForPickupEmailHandler
import pl.detailing.crm.shared.*
import pl.detailing.crm.smscampaigns.visitready.SendVisitReadyForPickupSmsCommand
import pl.detailing.crm.smscampaigns.visitready.SendVisitReadyForPickupSmsHandler
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository

@Service
class MarkVisitReadyForPickupHandler(
    private val visitRepository: VisitRepository,
    private val auditService: AuditService,
    private val sendVisitReadyForPickupEmailHandler: SendVisitReadyForPickupEmailHandler,
    private val sendVisitReadyForPickupSmsHandler: SendVisitReadyForPickupSmsHandler,
) {

    @Transactional
    suspend fun handle(command: MarkVisitReadyForPickupCommand): MarkVisitReadyForPickupResult {
        // Step 1: Load visit
        val visitEntity = visitRepository.findByIdAndStudioId(command.visitId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Visit with ID '${command.visitId}' not found")

        // Force load lazy collections within transaction
        visitEntity.serviceItems.size  // Force load serviceItems
        visitEntity.photos.size  // Force load photos

        val visit = visitEntity.toDomain()

        // Step 2: Perform state transition (domain logic with validation)
        val updatedVisit = visit.markAsReadyForPickup(command.userId)

        // Step 3: Persist changes
        val updatedEntity = VisitEntity.fromDomain(updatedVisit)
        visitRepository.save(updatedEntity)

        // Step 4: Audit logging
        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.VISIT,
            entityId = command.visitId.value.toString(),
            entityDisplayName = "Wizyta #${visit.visitNumber}",
            action = AuditAction.VISIT_MARKED_READY,
            changes = listOf(FieldChange("status", visit.status.name, updatedVisit.status.name))
        ))

        // Step 5: Side-effects
        if (command.sendEmail) {
            sendVisitReadyForPickupEmailHandler.handle(
                SendVisitReadyForPickupEmailCommand(
                    visitId = command.visitId,
                    studioId = command.studioId
                )
            )
        }

        if (command.sendSms) {
            sendVisitReadyForPickupSmsHandler.handle(
                SendVisitReadyForPickupSmsCommand(
                    visitId = command.visitId,
                    studioId = command.studioId
                )
            )
        }

        return MarkVisitReadyForPickupResult(
            visitId = updatedVisit.id,
            newStatus = updatedVisit.status
        )
    }
}

/**
 * Command to mark visit as ready for pickup
 */
data class MarkVisitReadyForPickupCommand(
    val studioId: StudioId,
    val userId: UserId,
    val visitId: VisitId,
    val sendSms: Boolean,
    val sendEmail: Boolean,
    val userName: String? = null
)

/**
 * Result of marking visit as ready for pickup
 */
data class MarkVisitReadyForPickupResult(
    val visitId: VisitId,
    val newStatus: VisitStatus
)
