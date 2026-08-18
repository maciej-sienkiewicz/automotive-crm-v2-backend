package pl.detailing.crm.leads.update

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.leads.domain.LeadCategory
import pl.detailing.crm.leads.domain.LeadLostReason
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.LeadChangedEvent
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Instant
import java.util.UUID

/**
 * User-driven lead edits. Status goes through [LeadStatusService] (history, closedAt,
 * mandatory loss reason); the rest are plain field updates plus one change event.
 */
@Service
class UpdateLeadHandlers(
    private val leadRepository: LeadRepository,
    private val statusService: LeadStatusService,
    private val serviceItems: LeadServiceItemsService,
    private val customerRepository: CustomerRepository,
    private val userRepository: UserRepository,
    private val eventPublisher: ApplicationEventPublisher
) {

    @Transactional
    fun changeStatus(
        studioId: StudioId,
        leadId: UUID,
        targetStatus: LeadStatus,
        lostReasonCode: LeadLostReason?,
        lostNote: String?,
        userId: UUID,
        userName: String
    ) {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")
        statusService.transition(
            lead = lead,
            targetStatus = targetStatus,
            lostReasonCode = lostReasonCode,
            lostNote = lostNote,
            changedByUserId = userId,
            changedByName = userName
        )
    }

    @Transactional
    fun updateServices(studioId: StudioId, leadId: UUID, items: List<LeadServiceItemInput>): Long {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")
        return serviceItems.replaceItems(lead, items)
    }

    @Transactional
    fun updateDetails(
        studioId: StudioId,
        leadId: UUID,
        category: LeadCategory?,
        customerName: String?,
        assignedUserId: UUID?
    ) {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")

        category?.let { lead.category = it }
        customerName?.let { lead.customerName = it.trim().take(200) }
        if (assignedUserId != null) {
            val user = userRepository.findById(assignedUserId).orElse(null)
                ?.takeIf { it.studioId == studioId.value }
                ?: throw NotFoundException("Nie znaleziono użytkownika")
            lead.assignedUserId = user.id
            lead.assignedUserName = "${user.firstName} ${user.lastName}".trim()
        }
        lead.updatedAt = Instant.now()
        leadRepository.save(lead)
        publishChanged(lead.studioId, lead.id)
    }

    @Transactional
    fun assignCustomer(studioId: StudioId, leadId: UUID, customerId: UUID) {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")
        val customer = customerRepository.findByIdAndStudioId(customerId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono klienta")

        lead.customerId = customer.id
        if (lead.customerName.isNullOrBlank()) {
            lead.customerName = listOfNotNull(customer.firstName, customer.lastName)
                .joinToString(" ").ifBlank { null }
        }
        lead.requiresVerification = false
        lead.updatedAt = Instant.now()
        leadRepository.save(lead)
        publishChanged(lead.studioId, lead.id)
    }

    private fun publishChanged(studioId: UUID, leadId: UUID) {
        eventPublisher.publishEvent(
            LeadChangedEvent(source = this, studioId = StudioId(studioId), leadId = LeadId(leadId))
        )
    }
}
