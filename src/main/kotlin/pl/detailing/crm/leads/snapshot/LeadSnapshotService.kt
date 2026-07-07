package pl.detailing.crm.leads.snapshot

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.leads.LeadDto
import pl.detailing.crm.leads.RelatedVisitDto
import pl.detailing.crm.leads.customer.CustomerSnapshot
import pl.detailing.crm.leads.estimation.infrastructure.LeadEstimationRepository
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.toDto
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId

/**
 * Assembles the full [LeadDto] for a single lead — the exact same shape as one row of
 * GET /api/v1/leads. Used by the WebSocket bridge for LEAD_UPDATED / LEAD_STATUS_CHANGED
 * events: the frontend replaces the whole cached row with the event payload, so any
 * missing field would disappear from the UI until the next refetch.
 */
@Service
class LeadSnapshotService(
    private val leadRepository: LeadRepository,
    private val leadEstimationRepository: LeadEstimationRepository,
    private val customerRepository: CustomerRepository
) {
    private val log = LoggerFactory.getLogger(LeadSnapshotService::class.java)

    fun getLeadDto(studioId: StudioId, leadId: LeadId): LeadDto? {
        val entity = leadRepository.findById(leadId.value).orElse(null)
        if (entity == null || entity.studioId != studioId.value) {
            log.debug("[LEAD_SNAPSHOT] Lead {} not found for studio {}", leadId.value, studioId.value)
            return null
        }

        val lead = entity.toDomain()
        val estimation = leadEstimationRepository.findByLeadId(leadId.value)
            ?.takeIf { it.studioId == studioId.value }

        val customer = lead.customerId?.let {
            customerRepository.findByIdAndStudioId(it.value, studioId.value)
        }

        return lead.toDto(
            estimationStatus = estimation?.status?.name,
            relatedVisits = estimation?.relatedVisits.orEmpty()
                .map { RelatedVisitDto(id = it.id, title = it.title) },
            summary = estimation?.aiSummary,
            // The dashboard topic is shared by the whole studio, so @Pii fields of this DTO
            // are masked at STOMP serialization (WebSocketEventBridge sends inside
            // PiiAccessContext.withMasked); entitled clients get full data on refetch.
            assignedCustomer = customer?.let {
                CustomerSnapshot(
                    id = it.id.toString(),
                    firstName = it.firstName,
                    lastName = it.lastName,
                    email = it.email,
                    phone = it.phone
                ).toDto()
            }
        )
    }
}
