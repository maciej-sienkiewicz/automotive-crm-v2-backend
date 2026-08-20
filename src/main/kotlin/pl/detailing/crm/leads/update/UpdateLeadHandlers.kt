package pl.detailing.crm.leads.update

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.leads.domain.LeadCategory
import pl.detailing.crm.leads.domain.LeadLostReason
import pl.detailing.crm.leads.domain.LeadVehicleDetectionStatus
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.tags.LeadTagCatalogService
import pl.detailing.crm.shared.LeadChangedEvent
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.vehicle.VehicleMetadataService
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
    private val vehicleMetadataService: VehicleMetadataService,
    private val tagService: LeadTagService,
    private val tagCatalog: LeadTagCatalogService,
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

    /**
     * Podmiana tagów istniejącego leada — zestaw w całości, tak jak przy tworzeniu.
     * Kody sprawdza katalog studia, więc do bazy nie wejdzie tag, którego nie ma
     * w słowniku i którego analityka nie umiałaby nazwać.
     */
    @Transactional
    fun updateTags(studioId: StudioId, leadId: UUID, tagCodes: List<String>) {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")
        tagService.replaceTags(lead.id, tagCatalog.validate(studioId, tagCodes))
        lead.updatedAt = Instant.now()
        leadRepository.save(lead)
        eventPublisher.publishEvent(
            LeadChangedEvent(source = this, studioId = studioId, leadId = LeadId(lead.id))
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

    /**
     * Ręczna korekta pojazdu. Rozpoznanie z korespondencji bywa niepełne (klient nie
     * podał auta) albo trafia obok — ostatnie słowo należy do człowieka.
     *
     * Wartości muszą pochodzić z katalogu, tak samo jak te od modelu: pole ma służyć
     * wyszukiwaniu i zestawieniom, więc wpisana ręcznie „bèemka" psułaby je dokładnie
     * tak samo jak surowy tekst od LLM-a.
     */
    @Transactional
    fun updateVehicle(studioId: StudioId, leadId: UUID, brand: String?, model: String?) {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")

        val cleanBrand = brand?.trim()?.takeIf { it.isNotBlank() }
        val cleanModel = model?.trim()?.takeIf { it.isNotBlank() }

        if (cleanBrand == null) {
            // Wyczyszczenie marki czyści też model — model bez marki nie znaczy nic.
            lead.vehicleBrand = null
            lead.vehicleModel = null
        } else {
            val canonicalBrand = vehicleMetadataService.getBrands()
                .firstOrNull { it.equals(cleanBrand, ignoreCase = true) }
                ?: throw ValidationException("Nieznana marka pojazdu: $cleanBrand")
            val canonicalModel = cleanModel?.let { raw ->
                vehicleMetadataService.getModelsForBrand(canonicalBrand)
                    .firstOrNull { it.equals(raw, ignoreCase = true) }
                    ?: throw ValidationException("Model $raw nie należy do marki $canonicalBrand")
            }
            lead.vehicleBrand = canonicalBrand
            lead.vehicleModel = canonicalModel
        }

        // Człowiek zamknął sprawę — spinner nie ma po co wracać, nawet gdyby
        // rozpoznanie z korespondencji dobiegło końca później.
        lead.vehicleDetectionStatus = LeadVehicleDetectionStatus.DONE
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
