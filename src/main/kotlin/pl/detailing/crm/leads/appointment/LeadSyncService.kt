package pl.detailing.crm.leads.appointment

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.update.LeadStatusService
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * Lead status transitions triggered by appointment/visit lifecycle events. This is
 * the "bezobsługowe śledzenie statusu": booking a reservation confirms the lead,
 * a finished visit completes it, a cancelled reservation marks a no-show — the user
 * never touches the pipeline by hand for the happy path.
 */
@Service
class LeadSyncService(
    private val leadRepository: LeadRepository,
    private val statusService: LeadStatusService,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(LeadSyncService::class.java)

    /** Called when an appointment is created from a lead (POST /appointments/from-lead/{leadId}). */
    @Transactional
    fun linkAppointment(
        leadEntity: LeadEntity,
        appointmentId: UUID,
        studioId: UUID,
        userId: UUID,
        userDisplayName: String
    ) {
        leadEntity.appointmentId = appointmentId
        leadEntity.requiresVerification = false
        leadEntity.updatedAt = Instant.now()
        leadRepository.save(leadEntity)
        statusService.transition(
            lead = leadEntity,
            targetStatus = LeadStatus.CONFIRMED,
            changedByUserId = userId,
            changedByName = userDisplayName
        )
    }

    /** Called when an appointment is cancelled or abandoned — the client didn't show up. */
    @Transactional
    fun markNoShow(appointmentId: UUID, studioId: UUID, userId: UUID, userDisplayName: String) {
        syncStatus(appointmentId, studioId, userId, userDisplayName, LeadStatus.NO_SHOW, visitId = null)
    }

    /** Called when a cancelled/abandoned appointment is restored. */
    @Transactional
    fun markConfirmed(appointmentId: UUID, studioId: UUID, userId: UUID, userDisplayName: String) {
        syncStatus(appointmentId, studioId, userId, userDisplayName, LeadStatus.CONFIRMED, visitId = null)
    }

    /** Called when a visit linked to this appointment is confirmed (protocols signed). */
    @Transactional
    fun markCompleted(appointmentId: UUID, visitId: UUID, studioId: UUID, userId: UUID, userDisplayName: String) {
        syncStatus(appointmentId, studioId, userId, userDisplayName, LeadStatus.COMPLETED, visitId)
    }

    private fun syncStatus(
        appointmentId: UUID,
        studioId: UUID,
        userId: UUID,
        userDisplayName: String,
        targetStatus: LeadStatus,
        visitId: UUID?
    ) {
        val lead = leadRepository.findByAppointmentId(appointmentId) ?: return

        val oldStatus = lead.status
        if (oldStatus == targetStatus) return

        if (visitId != null) {
            lead.visitId = visitId
            leadRepository.save(lead)
        }
        statusService.transition(
            lead = lead,
            targetStatus = targetStatus,
            changedByUserId = userId,
            changedByName = userDisplayName
        )

        log.info(
            "[LEADS] Status synced: leadId={}, appointmentId={}, {} → {}",
            lead.id, appointmentId, oldStatus, targetStatus
        )

        val auditAction = when (targetStatus) {
            LeadStatus.NO_SHOW -> AuditAction.LEAD_NO_SHOW
            LeadStatus.CONFIRMED -> AuditAction.LEAD_CONFIRMED
            LeadStatus.COMPLETED -> AuditAction.LEAD_COMPLETED
            else -> AuditAction.STATUS_CHANGE
        }

        auditService.logSync(
            LogAuditCommand(
                studioId = StudioId(studioId),
                userId = UserId(userId),
                userDisplayName = userDisplayName,
                module = AuditModule.LEAD,
                entityId = lead.id.toString(),
                entityDisplayName = lead.customerName,
                action = auditAction,
                changes = listOf(FieldChange("status", oldStatus.name, targetStatus.name)),
                metadata = buildMap {
                    put("appointmentId", appointmentId.toString())
                    if (visitId != null) put("visitId", visitId.toString())
                }
            )
        )
    }
}
