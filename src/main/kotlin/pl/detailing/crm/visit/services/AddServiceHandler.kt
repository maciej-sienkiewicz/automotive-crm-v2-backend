package pl.detailing.crm.visit.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.domain.AppointmentLineItem
import pl.detailing.crm.shared.VisitServiceItemId
import pl.detailing.crm.shared.VisitServiceStatus
import pl.detailing.crm.visit.domain.VisitServiceItem
import pl.detailing.crm.visit.infrastructure.VisitServiceItemEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.infrastructure.VisitServiceItemRepository
import java.time.Instant

@Service
class AddServiceHandler(
    private val validatorComposite: AddServiceValidatorComposite,
    private val visitRepository: VisitRepository,
    private val visitServiceItemRepository: VisitServiceItemRepository
) {
    @Transactional
    suspend fun handle(command: AddServiceCommand): AddServiceResult =
        withContext(Dispatchers.IO) {
            // Step 1: Validate
            val context = validatorComposite.validate(command)

            val service = context.service!!
            val visit = context.visit!!

            // Step 2: Calculate prices using the same logic as appointments
            // This creates a temporary line item just to calculate prices
            val lineItem = AppointmentLineItem.create(
                serviceId = service.id,
                serviceName = service.name,
                basePriceNet = service.basePriceNet,
                vatRate = service.vatRate,
                adjustmentType = command.adjustmentType,
                adjustmentValue = command.adjustmentValue,
                customNote = command.customNote
            )

            // Step 3: Create VisitServiceItem with PENDING status
            val serviceItemId = VisitServiceItemId.random()
            val serviceItem = VisitServiceItem(
                id = serviceItemId,
                serviceId = service.id,
                serviceName = service.name,
                basePriceNet = lineItem.basePriceNet,
                vatRate = lineItem.vatRate,
                adjustmentType = lineItem.adjustmentType,
                adjustmentValue = lineItem.adjustmentValue,
                finalPriceNet = lineItem.finalPriceNet,
                finalPriceGross = lineItem.finalPriceGross,
                status = VisitServiceStatus.PENDING, // Default status for manually added services
                customNote = command.customNote,
                createdAt = Instant.now()
            )

            // Step 4: Load visit entity and add service item
            val visitEntity = visitRepository.findByIdAndStudioId(
                visit.id.value,
                command.studioId.value
            )!!

            val serviceItemEntity = VisitServiceItemEntity.fromDomain(serviceItem, visitEntity)
            visitEntity.serviceItems.add(serviceItemEntity)
            visitEntity.updatedBy = command.userId.value
            visitEntity.updatedAt = Instant.now()

            // Step 5: Persist changes
            visitRepository.save(visitEntity)

            // Step 6: Return result
            AddServiceResult(
                serviceItemId = serviceItemId
            )
        }
}
