package pl.detailing.crm.checkin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.domain.AppointmentLineItem
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.customer.domain.Customer
import pl.detailing.crm.customer.domain.CompanyData
import pl.detailing.crm.customer.domain.CompanyAddress
import pl.detailing.crm.customer.domain.HomeAddress
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.domain.Vehicle
import pl.detailing.crm.vehicle.domain.VehicleOwner
import pl.detailing.crm.vehicle.infrastructure.VehicleEntity
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerEntity
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.visit.convert.VisitNumberGenerator
import pl.detailing.crm.visit.domain.Visit
import pl.detailing.crm.visit.domain.VisitServiceItem
import pl.detailing.crm.visit.infrastructure.DamageMarkingService
import pl.detailing.crm.visit.infrastructure.DocumentService
import pl.detailing.crm.visit.infrastructure.S3DamageMapStorageService
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

@Service
class CreateVisitFromReservationHandler(
    private val visitNumberGenerator: VisitNumberGenerator,
    private val visitRepository: VisitRepository,
    private val appointmentRepository: AppointmentRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val damageMarkingService: DamageMarkingService,
    private val s3DamageMapStorageService: S3DamageMapStorageService,
    private val documentService: DocumentService
) {
    @Transactional
    suspend fun handle(command: ReservationToVisitCommand): ReservationToVisitResult =
        withContext(Dispatchers.IO) {
            // Step 1: Load appointment
            val appointment = appointmentRepository.findByIdAndStudioId(
                command.reservationId.value,
                command.studioId.value
            )?.toDomain() ?: throw EntityNotFoundException("Appointment not found")

            // Step 2: Handle customer (create new or use existing)
            val customerId = when {
                command.customer == null -> {
                    // Customer alias only - we'll need to handle this case differently
                    // For now, throw exception as we need full customer data
                    throw ValidationException("Customer data required for check-in")
                }
                command.customer.isNew -> {
                    // Create new customer
                    createCustomer(command.customer, command.studioId, command.userId)
                }
                else -> {
                    // Use existing customer
                    command.customer.id ?: throw ValidationException("Customer ID required for existing customer")
                }
            }

            // Step 3: Handle vehicle (create new or use existing)
            val vehicleId = when {
                command.vehicle.isNew -> {
                    // Create new vehicle
                    createVehicle(command.vehicle, customerId, command.studioId, command.userId)
                }
                else -> {
                    // Use existing vehicle
                    command.vehicle.id ?: throw ValidationException("Vehicle ID required for existing vehicle")
                }
            }

            // Step 4: Load vehicle for snapshots
            val vehicle = vehicleRepository.findByIdAndStudioId(
                vehicleId.value,
                command.studioId.value
            )?.toDomain() ?: throw EntityNotFoundException("Vehicle not found")

            // Step 5: Generate visit number
            val visitNumber = visitNumberGenerator.generateVisitNumber(command.studioId)

            // Step 6: Map services to visit service items
            val serviceItems = command.services.map { serviceReq ->
                // Calculate prices using AppointmentLineItem logic
                val lineItem = AppointmentLineItem.create(
                    serviceId = ServiceId.fromString(serviceReq.serviceId),
                    serviceName = serviceReq.serviceName,
                    basePriceNet = Money.fromCents(serviceReq.basePriceNet),
                    vatRate = VatRate.fromInt(serviceReq.vatRate),
                    adjustmentType = AdjustmentType.valueOf(serviceReq.adjustment.type),
                    adjustmentValue = serviceReq.adjustment.value,
                    customNote = serviceReq.note
                )

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
                    status = VisitServiceStatus.CONFIRMED,
                    customNote = lineItem.customNote,
                    createdAt = Instant.now()
                )
            }

            // Step 7: Create Visit domain object
            val visitId = VisitId.random()
            var visit = Visit(
                id = visitId,
                studioId = command.studioId,
                visitNumber = visitNumber,
                customerId = customerId,
                vehicleId = vehicleId,
                appointmentId = appointment.id,
                appointmentColorId = command.appointmentColorId,
                // Immutable vehicle snapshots
                brandSnapshot = vehicle.brand,
                modelSnapshot = vehicle.model,
                licensePlateSnapshot = vehicle.licensePlate,
                vinSnapshot = null,
                yearOfProductionSnapshot = vehicle.yearOfProduction,
                colorSnapshot = vehicle.color,
                // Visit details
                status = VisitStatus.IN_PROGRESS,
                scheduledDate = appointment.schedule.startDateTime,
                completedDate = null,
                mileageAtArrival = command.technicalState.mileage,
                keysHandedOver = command.technicalState.deposit.keys,
                documentsHandedOver = command.technicalState.deposit.registrationDocument,
                inspectionNotes = command.technicalState.inspectionNotes,
                technicalNotes = null,
                serviceItems = serviceItems,
                photos = emptyList(), // Photos will be added separately
                damageMapFileId = null, // Will be set after generating damage map
                // Audit
                createdBy = command.userId,
                updatedBy = command.userId,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )

            // Step 8: Generate and upload damage map if damage points are provided
            if (command.damagePoints.isNotEmpty()) {
                try {
                    // Generate damage map image
                    val damageMapBytes = damageMarkingService.generateDamageMap(command.damagePoints)

                    if (damageMapBytes != null) {
                        // Upload to S3 and get the file ID
                        val damageMapFileId = s3DamageMapStorageService.uploadDamageMap(
                            studioId = command.studioId.value,
                            visitId = visitId.value,
                            imageBytes = damageMapBytes
                        )

                        // Update visit with damage map file ID
                        visit = visit.withDamageMap(damageMapFileId, command.userId)
                    }
                } catch (e: Exception) {
                    // Log error but don't fail the entire visit creation
                    // The visit can still be created without a damage map
                    println("Warning: Failed to generate damage map: ${e.message}")
                }
            }

            // Step 9: Persist Visit entity (must be done before registering documents)
            val visitEntity = VisitEntity.fromDomain(visit)
            visitRepository.save(visitEntity)

            // Step 9.5: Register damage map as a document if it was generated
            if (command.damagePoints.isNotEmpty() && visit.damageMapFileId != null) {
                try {
                    documentService.registerDocument(
                        visitId = visitId.value,
                        customerId = customerId.value,
                        documentType = DocumentType.DAMAGE_MAP,
                        name = "Damage Map - ${visit.visitNumber}",
                        s3Key = visit.damageMapFileId!!,
                        fileName = "damage-map.jpg",
                        createdBy = command.userId.value,
                        createdByName = "System",
                        category = "damage"
                    )
                } catch (e: Exception) {
                    // Log error but don't fail the visit creation
                    println("Warning: Failed to register damage map document: ${e.message}")
                }
            }

            // Step 10: Update appointment status to CONVERTED
            val appointmentEntity = appointmentRepository.findByIdAndStudioId(
                appointment.id.value,
                command.studioId.value
            )!!
            appointmentEntity.status = AppointmentStatus.CONVERTED
            appointmentEntity.updatedBy = command.userId.value
            appointmentEntity.updatedAt = Instant.now()
            appointmentRepository.save(appointmentEntity)

            // Step 11: Return result
            ReservationToVisitResult(visitId = visitId)
        }

    private fun createCustomer(
        customerData: CustomerData,
        studioId: StudioId,
        userId: UserId
    ): CustomerId {
        val customer = Customer(
            id = CustomerId.random(),
            studioId = studioId,
            firstName = customerData.firstName.trim(),
            lastName = customerData.lastName.trim(),
            email = customerData.email.trim().lowercase(),
            phone = customerData.phone.trim(),
            homeAddress = customerData.homeAddress?.let {
                HomeAddress(
                    street = it.street,
                    city = it.city,
                    postalCode = it.postalCode,
                    country = it.country
                )
            },
            companyData = customerData.company?.let {
                CompanyData(
                    name = it.name,
                    nip = it.nip,
                    regon = it.regon,
                    address = CompanyAddress(
                        street = it.address.street,
                        city = it.address.city,
                        postalCode = it.address.postalCode,
                        country = it.address.country
                    )
                )
            },
            notes = null,
            isActive = true,
            createdBy = userId,
            updatedBy = userId,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        val entity = CustomerEntity.fromDomain(customer)
        customerRepository.save(entity)

        return customer.id
    }

    private fun createVehicle(
        vehicleData: VehicleData,
        customerId: CustomerId,
        studioId: StudioId,
        userId: UserId
    ): VehicleId {
        val vehicle = Vehicle(
            id = VehicleId.random(),
            studioId = studioId,
            licensePlate = vehicleData.licensePlate?.trim()?.uppercase(),
            brand = vehicleData.brand.trim(),
            model = vehicleData.model.trim(),
            yearOfProduction = vehicleData.yearOfProduction,
            color = vehicleData.color?.trim(),
            paintType = vehicleData.paintType?.trim(),
            currentMileage = 0, // Will be set from mileageAtArrival
            status = VehicleStatus.ACTIVE,
            createdBy = userId,
            updatedBy = userId,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        val vehicleEntity = VehicleEntity.fromDomain(vehicle)
        vehicleRepository.save(vehicleEntity)

        // Link vehicle to customer
        val vehicleOwner = VehicleOwner(
            vehicleId = vehicle.id,
            customerId = customerId,
            ownershipRole = OwnershipRole.PRIMARY,
            assignedAt = Instant.now()
        )

        val vehicleOwnerEntity = VehicleOwnerEntity.fromDomain(vehicleOwner)
        vehicleOwnerRepository.save(vehicleOwnerEntity)

        return vehicle.id
    }
}
