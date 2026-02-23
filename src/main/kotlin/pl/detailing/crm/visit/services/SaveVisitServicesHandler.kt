package pl.detailing.crm.visit.services

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.visit.domain.VisitServiceItem
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.shared.*
import java.util.*

@Service
class SaveVisitServicesHandler(
    private val visitRepository: VisitRepository,
    private val auditService: AuditService
) {

    @Transactional
    suspend fun handle(visitId: VisitId, studioId: StudioId, userId: UserId, payload: ServicesChangesPayload, userName: String? = null) {
        val visitEntity = visitRepository.findByIdAndStudioId(visitId.value, studioId.value)
            ?: throw EntityNotFoundException("Visit $visitId not found in studio $studioId")

        // Force load lazy collection
        visitEntity.serviceItems.size

        val visit = visitEntity.toDomain()

        val addedItems = payload.added.map { added ->
            val adjustmentType = added.adjustment?.type ?: pl.detailing.crm.appointment.domain.AdjustmentType.PERCENT
            val adjustmentValue = added.adjustment?.value ?: 0.0

            // Convert adjustment value based on type:
            // - For PERCENT: validate non-negative and convert using semantic convention
            //   (0–100 = discount, >100 = markup) to basis points
            // - For others: round to Long (cents)
            val adjustmentValueLong = when (adjustmentType) {
                pl.detailing.crm.appointment.domain.AdjustmentType.PERCENT ->
                    pl.detailing.crm.appointment.domain.AdjustmentType.convertPercentValueToBasisPoints(adjustmentValue)
                else -> adjustmentValue.toLong()
            }

            VisitServiceItem.createPending(
                serviceId = added.serviceId?.let { ServiceId.fromString(it) },
                serviceName = added.serviceName,
                basePriceNet = Money(added.basePriceNet),
                vatRate = VatRate.fromInt(added.vatRate),
                adjustmentType = adjustmentType,
                adjustmentValue = adjustmentValueLong,
                customNote = added.note
            )
        }

        val updatedItems = payload.updated.map { updated ->
            val existingItem = visit.serviceItems.find { it.id.value.toString() == updated.serviceLineItemId }
                ?: throw EntityNotFoundException("Service item ${updated.serviceLineItemId} not found in visit $visitId")
            
            existingItem.toPending(Money(updated.basePriceNet))
        }

        val deletedItems = payload.deleted.map { deleted ->
            val existingItem = visit.serviceItems.find { it.id.value.toString() == deleted.serviceLineItemId }
                ?: throw EntityNotFoundException("Service item ${deleted.serviceLineItemId} not found in visit $visitId")
            
            existingItem.markForDeletion()
        }

        val updatedVisit = visit.saveServicesChanges(
            added = addedItems,
            updated = updatedItems + deletedItems,  // Deleted items are now updated items with DELETE operation
            deletedIds = emptyList(),  // No physical deletion
            updatedBy = userId
        )

        val updatedEntity = VisitEntity.fromDomain(updatedVisit)
        visitRepository.save(updatedEntity)

        val changes = mutableListOf<FieldChange>()
        if (payload.added.isNotEmpty()) {
            changes.add(FieldChange("servicesAdded", null, payload.added.size.toString()))
        }
        if (payload.updated.isNotEmpty()) {
            changes.add(FieldChange("servicesUpdated", null, payload.updated.size.toString()))
        }
        if (payload.deleted.isNotEmpty()) {
            changes.add(FieldChange("servicesDeleted", null, payload.deleted.size.toString()))
        }

        auditService.log(LogAuditCommand(
            studioId = studioId,
            userId = userId,
            userDisplayName = userName ?: "",
            module = AuditModule.VISIT,
            entityId = visitId.value.toString(),
            entityDisplayName = "Wizyta #${visitEntity.visitNumber}",
            action = AuditAction.SERVICES_UPDATED,
            changes = changes
        ))
    }
}
