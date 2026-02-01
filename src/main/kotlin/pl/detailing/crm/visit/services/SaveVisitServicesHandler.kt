package pl.detailing.crm.visit.services

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.visit.domain.VisitServiceItem
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.shared.*
import java.util.*

@Service
class SaveVisitServicesHandler(
    private val visitRepository: VisitRepository
) {

    @Transactional
    suspend fun handle(visitId: VisitId, studioId: StudioId, userId: UserId, payload: ServicesChangesPayload) {
        val visitEntity = visitRepository.findByIdAndStudioId(visitId.value, studioId.value)
            ?: throw EntityNotFoundException("Visit $visitId not found in studio $studioId")

        // Force load lazy collection
        visitEntity.serviceItems.size

        val visit = visitEntity.toDomain()

        val addedItems = payload.added.map { added ->
            VisitServiceItem.createPending(
                serviceId = added.serviceId?.let { ServiceId.fromString(it) },
                serviceName = added.serviceName,
                basePriceNet = Money(added.basePriceNet),
                vatRate = VatRate.fromInt(added.vatRate),
                adjustmentType = added.adjustment?.type ?: pl.detailing.crm.appointment.domain.AdjustmentType.PERCENT,
                adjustmentValue = added.adjustment?.value ?: 0L,
                customNote = added.note
            )
        }

        val updatedItems = payload.updated.map { updated ->
            val existingItem = visit.serviceItems.find { it.id.value.toString() == updated.serviceLineItemId }
                ?: throw EntityNotFoundException("Service item ${updated.serviceLineItemId} not found in visit $visitId")
            
            existingItem.toPending(Money(updated.basePriceNet))
        }

        val deletedIds = payload.deleted.map { VisitServiceItemId(UUID.fromString(it.serviceLineItemId)) }

        val updatedVisit = visit.saveServicesChanges(
            added = addedItems,
            updated = updatedItems,
            deletedIds = deletedIds,
            updatedBy = userId
        )

        val updatedEntity = VisitEntity.fromDomain(updatedVisit)
        visitRepository.save(updatedEntity)
    }
}
