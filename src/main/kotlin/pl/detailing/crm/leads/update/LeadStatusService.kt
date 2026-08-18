package pl.detailing.crm.leads.update

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.domain.LeadLostReason
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadStatusHistoryEntity
import pl.detailing.crm.leads.infrastructure.LeadStatusHistoryRepository
import pl.detailing.crm.shared.LeadChangedEvent
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * The single owner of lead status transitions. Every change — user click, appointment
 * lifecycle, no-show job — goes through here, so the append-only history behind the
 * analytics can never drift from the lead's current state.
 */
@Service
class LeadStatusService(
    private val leadRepository: LeadRepository,
    private val historyRepository: LeadStatusHistoryRepository,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun transition(
        lead: LeadEntity,
        targetStatus: LeadStatus,
        lostReasonCode: LeadLostReason? = null,
        lostNote: String? = null,
        changedByUserId: UUID? = null,
        changedByName: String? = null
    ) {
        val oldStatus = lead.status
        if (oldStatus == targetStatus) return

        // Closing as lost without a dictionary reason would silently break the
        // loss analytics — this is the one mandatory field in the whole flow.
        if (targetStatus == LeadStatus.LOST && lostReasonCode == null) {
            throw ValidationException("Wybierz powód przegranej — to jedyne wymagane pole")
        }

        lead.status = targetStatus
        lead.updatedAt = Instant.now()
        if (targetStatus == LeadStatus.LOST) {
            lead.lostReasonCode = lostReasonCode
            lead.lostReason = lostNote?.take(500)
        }
        lead.closedAt = if (targetStatus in TERMINAL_STATUSES) Instant.now() else null
        leadRepository.save(lead)

        historyRepository.save(
            LeadStatusHistoryEntity(
                id = UUID.randomUUID(),
                studioId = lead.studioId,
                leadId = lead.id,
                fromStatus = oldStatus,
                toStatus = targetStatus,
                lostReasonCode = if (targetStatus == LeadStatus.LOST) lostReasonCode else null,
                changedByUserId = changedByUserId,
                changedByName = changedByName
            )
        )

        eventPublisher.publishEvent(
            LeadChangedEvent(
                source = this,
                studioId = StudioId(lead.studioId),
                leadId = LeadId(lead.id),
                statusChanged = true
            )
        )
        log.info("[LEADS] {} → {} (lead={})", oldStatus, targetStatus, lead.id)
    }

    /** History entry for a freshly created lead, so funnels start at the true beginning. */
    @Transactional
    fun recordCreation(lead: LeadEntity, createdByUserId: UUID?, createdByName: String?) {
        historyRepository.save(
            LeadStatusHistoryEntity(
                id = UUID.randomUUID(),
                studioId = lead.studioId,
                leadId = lead.id,
                fromStatus = null,
                toStatus = lead.status,
                lostReasonCode = null,
                changedByUserId = createdByUserId,
                changedByName = createdByName
            )
        )
    }

    companion object {
        val TERMINAL_STATUSES = setOf(LeadStatus.COMPLETED, LeadStatus.LOST, LeadStatus.NO_SHOW)
    }
}
