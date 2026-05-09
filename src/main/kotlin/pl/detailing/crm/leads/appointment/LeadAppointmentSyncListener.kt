package pl.detailing.crm.leads.appointment

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.events.AppointmentLeadSyncEvent
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant

/**
 * Listens for appointment lifecycle events and synchronises the status of
 * any lead that was created from that appointment.
 *
 * Runs in a separate transaction (REQUIRES_NEW) so that a failure here
 * never rolls back the appointment status change that triggered the event.
 */
@Component
class LeadAppointmentSyncListener(
    private val leadRepository: LeadRepository,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(LeadAppointmentSyncListener::class.java)

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onAppointmentLeadSync(event: AppointmentLeadSyncEvent) {
        val lead = leadRepository.findByAppointmentId(event.appointmentId) ?: return

        val oldStatus = lead.status
        if (oldStatus == event.targetLeadStatus) return

        lead.status = event.targetLeadStatus
        if (event.visitId != null) {
            lead.visitId = event.visitId
        }
        lead.updatedAt = Instant.now()
        leadRepository.save(lead)

        log.info(
            "[LEADS] Lead status synced from appointment event: leadId={}, appointmentId={}, {} → {}",
            lead.id, event.appointmentId, oldStatus, event.targetLeadStatus
        )

        val auditAction = when (event.targetLeadStatus) {
            LeadStatus.NO_SHOW   -> AuditAction.LEAD_NO_SHOW
            LeadStatus.CONFIRMED -> AuditAction.LEAD_CONFIRMED
            LeadStatus.COMPLETED -> AuditAction.LEAD_COMPLETED
            else                 -> AuditAction.STATUS_CHANGE
        }

        auditService.logSync(
            LogAuditCommand(
                studioId = StudioId(event.studioId),
                userId = UserId(event.initiatorUserId),
                userDisplayName = event.initiatorDisplayName,
                module = AuditModule.LEAD,
                entityId = lead.id.toString(),
                entityDisplayName = lead.customerName,
                action = auditAction,
                changes = listOf(FieldChange("status", oldStatus.name, event.targetLeadStatus.name)),
                metadata = buildMap {
                    put("appointmentId", event.appointmentId.toString())
                    if (event.visitId != null) put("visitId", event.visitId.toString())
                }
            )
        )
    }
}
