package pl.detailing.crm.visit.convert

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.domain.Visit
import pl.detailing.crm.visit.domain.VisitServiceItem
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

@Service
class ConvertToVisitHandler(
    private val validatorComposite: ConvertToVisitValidatorComposite,
    private val visitNumberGenerator: VisitNumberGenerator,
    private val visitRepository: VisitRepository,
    private val appointmentRepository: AppointmentRepository
) {
    @Transactional
    suspend fun handle(command: ConvertToVisitCommand): ConvertToVisitResult =
        withContext(Dispatchers.IO) {
            // Step 1: Validate
            val context = validatorComposite.validate(command)

            val appointment = context.appointment!!
            val vehicle = context.vehicle!!

            // Step 2: Generate visit number
            val visitNumber = visitNumberGenerator.generateVisitNumber(command.studioId)

            // Step 3: Create vehicle snapshots
            val visitId = VisitId.random()

            // Step 4: Map services from appointment to visit service items
            // All services from appointment are set to APPROVED status
            val serviceItems = appointment.lineItems.map { lineItem ->
                VisitServiceItem(
                    id = VisitServiceItemId.random(),
                    serviceId = lineItem.serviceId,
                    serviceName = lineItem.serviceName,
                    basePriceNet = lineItem.basePriceNet,
                    vatRate = lineItem.vatRate,
                    adjustmentType = lineItem.adjustmentType,
                    adjustmentValue = lineItem.adjustmentValue,
                    finalPriceNet = lineItem.finalPriceNet,
                    finalPriceGross = lineItem.finalPriceGross,
                    status = VisitServiceStatus.APPROVED, // All initial services are APPROVED
                    customNote = lineItem.customNote,
                    createdAt = Instant.now()
                )
            }

            // Step 5: Create Visit domain object
            val visit = Visit(
                id = visitId,
                studioId = command.studioId,
                visitNumber = visitNumber,
                customerId = appointment.customerId,
                vehicleId = vehicle.id,
                appointmentId = appointment.id,
                // Immutable vehicle snapshots
                brandSnapshot = vehicle.brand,
                modelSnapshot = vehicle.model,
                licensePlateSnapshot = vehicle.licensePlate,
                vinSnapshot = vehicle.vin,
                yearOfProductionSnapshot = vehicle.yearOfProduction,
                colorSnapshot = vehicle.color,
                engineTypeSnapshot = vehicle.engineType,
                // Visit details
                status = VisitStatus.ACCEPTED,
                scheduledDate = appointment.schedule.startDateTime,
                completedDate = null,
                mileageAtArrival = command.mileageAtArrival,
                keysHandedOver = command.keysHandedOver,
                documentsHandedOver = command.documentsHandedOver,
                technicalNotes = command.technicalNotes,
                serviceItems = serviceItems,
                // Audit
                createdBy = command.userId,
                updatedBy = command.userId,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )

            // Step 6: Persist Visit entity
            val visitEntity = VisitEntity.fromDomain(visit)
            visitRepository.save(visitEntity)

            // Step 7: Update appointment status to CONVERTED
            val appointmentEntity = appointmentRepository.findByIdAndStudioId(
                appointment.id.value,
                command.studioId.value
            )!!
            appointmentEntity.status = AppointmentStatus.CONVERTED
            appointmentEntity.updatedBy = command.userId.value
            appointmentEntity.updatedAt = Instant.now()
            appointmentRepository.save(appointmentEntity)

            // Step 8: Return result
            ConvertToVisitResult(
                visitId = visitId,
                visitNumber = visitNumber
            )
        }
}
