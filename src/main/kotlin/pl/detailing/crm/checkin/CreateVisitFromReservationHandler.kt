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
    private val documentService: DocumentService,
    private val serviceRepository: pl.detailing.crm.service.infrastructure.ServiceRepository
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
            val customerId = when (val customerData = command.customer) {
                null -> {
                    // Customer alias only - we'll need to handle this case differently
                    // For now, throw exception as we need full customer data
                    throw ValidationException("Customer data required for check-in")
                }
                is CustomerData.New -> {
                    // Create new customer
                    createCustomer(customerData, command.studioId, command.userId)
                }
                is CustomerData.Existing -> {
                    customerData.id
                }
                is CustomerData.Update -> {
                    // Use existing customer and update if data changed
                    updateCustomerIfNeeded(
                        customerData.id,
                        customerData,
                        command.studioId,
                        command.userId
                    )
                    customerData.id
                }
            }

            // Step 3: Handle vehicle (create new or use existing)
            val vehicleId = when (val vehicleData = command.vehicle) {
                is VehicleData.New -> {
                    // Create new vehicle
                    createVehicle(vehicleData, customerId, command.studioId, command.userId)
                }
                is VehicleData.Existing -> {
                    vehicleData.id
                }
                is VehicleData.Update -> {
                    updateVehicleIfNeeded(
                        vehicleData.id,
                        vehicleData,
                        command.studioId,
                        command.userId
                    )
                    vehicleData.id
                }
            }

            // Step 4: Load vehicle for snapshots
            val vehicle = vehicleRepository.findByIdAndStudioId(
                vehicleId.value,
                command.studioId.value
            )?.toDomain() ?: throw EntityNotFoundException("Vehicle not found")

            // Step 5: Generate visit number
            val visitNumber = visitNumberGenerator.generateVisitNumber(command.studioId)

            // Step 5.5: Load services and validate manual price requirements
            val requestedServiceIds = command.services.mapNotNull { it.serviceId?.let { id -> ServiceId.fromString(id) } }
            val services = serviceRepository.findActiveByStudioId(command.studioId.value)
                .filter { it.id in requestedServiceIds.map { id -> id.value } }
                .map { it.toDomain() }
                .associateBy { it.id }

            // Validate that services requiring manual price have explicit price set
            command.services.forEach { serviceReq ->
                val service = serviceReq.serviceId?.let { services[ServiceId.fromString(it)] }
                if (service?.requireManualPrice == true) {
                    val adjustmentType = AdjustmentType.valueOf(serviceReq.adjustment.type)
                    if (adjustmentType != AdjustmentType.SET_NET && adjustmentType != AdjustmentType.SET_GROSS) {
                        throw ValidationException(
                            "Service '${service.name}' requires manual price input. " +
                            "Please provide an explicit price using SET_NET or SET_GROSS adjustment type."
                        )
                    }
                }
            }

            // Step 6: Map services to visit service items
            val serviceItems = command.services.map { serviceReq ->
                val adjustmentType = AdjustmentType.valueOf(serviceReq.adjustment.type)

                // Convert adjustment value based on type:
                // - For PERCENT: convert to basis points (multiply by 100) to preserve decimals
                // - For others: round to Long (cents)
                val adjustmentValue = when (adjustmentType) {
                    AdjustmentType.PERCENT -> (serviceReq.adjustment.value * 100).toLong() // Convert to basis points
                    else -> serviceReq.adjustment.value.toLong() // Keep in cents
                }

                // Calculate prices using AppointmentLineItem logic
                val lineItem = AppointmentLineItem.create(
                    serviceId = serviceReq.serviceId?.let { ServiceId.fromString(it) },
                    serviceName = serviceReq.serviceName,
                    basePriceNet = Money.fromCents(serviceReq.basePriceNet),
                    vatRate = VatRate.fromInt(serviceReq.vatRate),
                    adjustmentType = adjustmentType,
                    adjustmentValue = adjustmentValue,
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
                    pendingOperation = null,
                    confirmedSnapshot = null,
                    customNote = lineItem.customNote,
                    createdAt = Instant.now(),
                    confirmedAt = Instant.now(),
                    pendingAt = null
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
                status = VisitStatus.DRAFT,  // Start in DRAFT - will be confirmed after document signing
                scheduledDate = appointment.schedule.startDateTime,
                estimatedCompletionDate = appointment.schedule.endDateTime,
                actualCompletionDate = null,
                pickupDate = null,
                mileageAtArrival = command.technicalState.mileage,
                keysHandedOver = command.technicalState.deposit.keys,
                documentsHandedOver = command.technicalState.deposit.registrationDocument,
                inspectionNotes = listOfNotNull(appointment.note, command.technicalState.inspectionNotes)
                    .joinToString("\n")
                    .ifBlank { null },
                technicalNotes = listOfNotNull(appointment.note, command.technicalState.inspectionNotes)
                    .joinToString("\n")
                    .ifBlank { null }, // Also save to technicalNotes for visibility in API response
                vehicleHandoff = command.vehicleHandoff,
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

            // Step 10: DO NOT update appointment status yet
            // Appointment will be marked as CONVERTED only when visit is confirmed (after documents are signed)
            // This allows users to cancel the draft visit and return to reservation

            // Step 11: Return result
            ReservationToVisitResult(visitId = visitId)
        }

    private fun createCustomer(
        customerData: CustomerData,
        studioId: StudioId,
        userId: UserId
    ): CustomerId {
        val firstName: String
        val lastName: String
        val email: String?
        val phone: String?
        val homeAddress: HomeAddressRequest?
        val company: CompanyRequest?

        when (customerData) {
            is CustomerData.New -> {
                firstName = customerData.firstName
                lastName = customerData.lastName
                email = customerData.email
                phone = customerData.phone
                homeAddress = customerData.homeAddress
                company = customerData.company
            }
            is CustomerData.Update -> {
                firstName = customerData.firstName
                lastName = customerData.lastName
                email = customerData.email
                phone = customerData.phone
                homeAddress = customerData.homeAddress
                company = customerData.company
            }
            else -> throw IllegalArgumentException("Cannot create customer from Existing state")
        }

        val customer = Customer(
            id = CustomerId.random(),
            studioId = studioId,
            firstName = firstName.trim(),
            lastName = lastName.trim(),
            email = email?.trim()?.lowercase(),
            phone = phone?.trim(),
            homeAddress = homeAddress?.let {
                HomeAddress(
                    street = it.street,
                    city = it.city,
                    postalCode = it.postalCode,
                    country = it.country
                )
            },
            companyData = company?.let {
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
        vehicleData: VehicleData.New,
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

    private fun updateCustomerIfNeeded(
        customerId: CustomerId,
        customerData: CustomerData.Update,
        studioId: StudioId,
        userId: UserId
    ) {
        val existingEntity = customerRepository.findByIdAndStudioId(customerId.value, studioId.value)
            ?: throw EntityNotFoundException("Customer not found")

        // Check if any data has changed
        val newFirstName = customerData.firstName.trim()
        val newLastName = customerData.lastName.trim()
        val newEmail = customerData.email?.trim()?.lowercase()
        val newPhone = customerData.phone?.trim()

        val newHomeAddress = customerData.homeAddress
        val newCompany = customerData.company

        val hasBasicDataChanged = existingEntity.firstName != newFirstName ||
            existingEntity.lastName != newLastName ||
            existingEntity.email != newEmail ||
            existingEntity.phone != newPhone

        val hasHomeAddressChanged = when {
            newHomeAddress == null && existingEntity.homeAddressStreet == null -> false
            newHomeAddress == null -> true // Was set, now null
            existingEntity.homeAddressStreet == null -> true // Was null, now set
            else -> existingEntity.homeAddressStreet != newHomeAddress.street ||
                existingEntity.homeAddressCity != newHomeAddress.city ||
                existingEntity.homeAddressPostalCode != newHomeAddress.postalCode ||
                existingEntity.homeAddressCountry != newHomeAddress.country
        }

        val hasCompanyDataChanged = when {
            newCompany == null && existingEntity.companyName == null -> false
            newCompany == null -> true // Was set, now null
            existingEntity.companyName == null -> true // Was null, now set
            else -> existingEntity.companyName != newCompany.name ||
                existingEntity.companyNip != newCompany.nip ||
                existingEntity.companyRegon != newCompany.regon ||
                existingEntity.companyAddressStreet != newCompany.address.street ||
                existingEntity.companyAddressCity != newCompany.address.city ||
                existingEntity.companyAddressPostalCode != newCompany.address.postalCode ||
                existingEntity.companyAddressCountry != newCompany.address.country
        }

        if (hasBasicDataChanged || hasHomeAddressChanged || hasCompanyDataChanged) {
            // Update the entity
            existingEntity.firstName = newFirstName
            existingEntity.lastName = newLastName
            existingEntity.email = newEmail
            existingEntity.phone = newPhone

            // Update home address
            if (newHomeAddress != null) {
                existingEntity.homeAddressStreet = newHomeAddress.street
                existingEntity.homeAddressCity = newHomeAddress.city
                existingEntity.homeAddressPostalCode = newHomeAddress.postalCode
                existingEntity.homeAddressCountry = newHomeAddress.country
            } else {
                existingEntity.homeAddressStreet = null
                existingEntity.homeAddressCity = null
                existingEntity.homeAddressPostalCode = null
                existingEntity.homeAddressCountry = null
            }

            // Update company data
            if (newCompany != null) {
                existingEntity.companyName = newCompany.name
                existingEntity.companyNip = newCompany.nip
                existingEntity.companyRegon = newCompany.regon
                existingEntity.companyAddressStreet = newCompany.address.street
                existingEntity.companyAddressCity = newCompany.address.city
                existingEntity.companyAddressPostalCode = newCompany.address.postalCode
                existingEntity.companyAddressCountry = newCompany.address.country
            } else {
                existingEntity.companyName = null
                existingEntity.companyNip = null
                existingEntity.companyRegon = null
                existingEntity.companyAddressStreet = null
                existingEntity.companyAddressCity = null
                existingEntity.companyAddressPostalCode = null
                existingEntity.companyAddressCountry = null
            }

            existingEntity.updatedBy = userId.value
            existingEntity.updatedAt = Instant.now()

            customerRepository.save(existingEntity)
        }
    }

    private fun updateVehicleIfNeeded(
        vehicleId: VehicleId,
        vehicleData: VehicleData.Update,
        studioId: StudioId,
        userId: UserId
    ) {
        val existingEntity = vehicleRepository.findByIdAndStudioId(vehicleId.value, studioId.value)
            ?: throw EntityNotFoundException("Vehicle not found")

        val hasChanged = existingEntity.brand != vehicleData.brand.trim() ||
            existingEntity.model != vehicleData.model.trim() ||
            existingEntity.yearOfProduction != vehicleData.yearOfProduction ||
            existingEntity.licensePlate != vehicleData.licensePlate?.trim()?.uppercase() ||
            existingEntity.color != vehicleData.color?.trim() ||
            existingEntity.paintType != vehicleData.paintType?.trim()

        if (hasChanged) {
            existingEntity.brand = vehicleData.brand.trim()
            existingEntity.model = vehicleData.model.trim()
            existingEntity.yearOfProduction = vehicleData.yearOfProduction
            existingEntity.licensePlate = vehicleData.licensePlate?.trim()?.uppercase()
            existingEntity.color = vehicleData.color?.trim()
            existingEntity.paintType = vehicleData.paintType?.trim()
            existingEntity.updatedBy = userId.value
            existingEntity.updatedAt = Instant.now()
            vehicleRepository.save(existingEntity)
        }
    }
}
