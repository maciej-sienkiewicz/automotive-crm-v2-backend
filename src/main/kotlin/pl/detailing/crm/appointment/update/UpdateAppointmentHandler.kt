package pl.detailing.crm.appointment.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.create.*
import pl.detailing.crm.appointment.domain.*
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentLineItemEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.customer.domain.Customer
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.domain.Vehicle
import pl.detailing.crm.vehicle.domain.VehicleOwner
import pl.detailing.crm.vehicle.infrastructure.VehicleEntity
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerEntity
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.Instant

@Service
class UpdateAppointmentHandler(
    private val validatorComposite: CreateAppointmentValidatorComposite,
    private val appointmentRepository: AppointmentRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val serviceRepository: ServiceRepository
) {

    @Transactional
    suspend fun handle(command: UpdateAppointmentCommand): CreateAppointmentResult = withContext(Dispatchers.IO) {
        // Step 1: Find existing appointment
        val existingEntity = appointmentRepository.findByIdAndStudioId(command.appointmentId.value, command.studioId.value)
            ?: throw NotFoundException("Appointment not found")

        // Step 2: Validation (reuse create validation for now, as it covers core rules)
        validatorComposite.validate(command.toCreateCommand())

        // Step 3: Identity Resolution - Customer (reusing create logic)
        val customerId = when (val identity = command.customer) {
            is CustomerIdentity.Existing -> identity.customerId
            is CustomerIdentity.New -> createCustomer(identity, command.studioId, command.userId)
            is CustomerIdentity.Update -> updateCustomer(identity, command.studioId, command.userId)
        }

        // Step 4: Identity Resolution - Vehicle (reusing create logic)
        val vehicleId = when (val identity = command.vehicle) {
            is VehicleIdentity.Existing -> identity.vehicleId
            is VehicleIdentity.New -> createVehicle(identity, customerId, command.studioId, command.userId)
            is VehicleIdentity.Update -> updateVehicle(identity, command.studioId, command.userId)
            VehicleIdentity.None -> null
        }

        // Step 5: Fetch Services and Create Line Items
        val services = serviceRepository.findActiveByStudioId(command.studioId.value)
            .filter { it.id in command.services.map { svc -> svc.serviceId.value } }
            .map { it.toDomain() }
            .associateBy { it.id }

        val lineItems = command.services.map { serviceLineItem ->
            val service = services[serviceLineItem.serviceId]
                ?: throw EntityNotFoundException("Service with ID '${serviceLineItem.serviceId}' not found")

            AppointmentLineItem.create(
                serviceId = service.id,
                serviceName = service.name,
                basePriceNet = service.basePriceNet,
                vatRate = service.vatRate,
                adjustmentType = serviceLineItem.adjustmentType,
                adjustmentValue = serviceLineItem.adjustmentValue,
                customNote = serviceLineItem.customNote
            )
        }

        // Step 6: Update Appointment Entity
        existingEntity.customerId = customerId.value
        existingEntity.vehicleId = vehicleId?.value
        existingEntity.appointmentTitle = command.appointmentTitle
        existingEntity.appointmentColorId = command.appointmentColorId.value
        existingEntity.note = command.note
        existingEntity.isAllDay = command.schedule.isAllDay
        existingEntity.startDateTime = command.schedule.startDateTime
        existingEntity.endDateTime = command.schedule.endDateTime
        existingEntity.updatedBy = command.userId.value
        existingEntity.updatedAt = Instant.now()

        // Update line items
        existingEntity.lineItems.clear()
        existingEntity.lineItems.addAll(lineItems.map { AppointmentLineItemEntity.fromDomain(it, existingEntity) })

        // Step 7: Persist Appointment
        appointmentRepository.save(existingEntity)

        val updatedDomain = existingEntity.toDomain()

        CreateAppointmentResult(
            appointmentId = updatedDomain.id,
            customerId = customerId,
            vehicleId = vehicleId,
            totalNet = updatedDomain.calculateTotalNet(),
            totalGross = updatedDomain.calculateTotalGross(),
            totalVat = updatedDomain.calculateTotalVat()
        )
    }

    private fun createCustomer(
        identity: CustomerIdentity.New,
        studioId: StudioId,
        userId: UserId
    ): CustomerId {
        val customer = Customer(
            id = CustomerId.random(),
            studioId = studioId,
            firstName = identity.firstName?.trim(),
            lastName = identity.lastName?.trim(),
            email = identity.email?.trim()?.lowercase(),
            phone = identity.phone?.trim(),
            homeAddress = null,
            companyData = if (identity.companyName != null) {
                pl.detailing.crm.customer.domain.CompanyData(
                    name = identity.companyName,
                    nip = identity.companyNip,
                    regon = identity.companyRegon,
                    address = if (identity.companyAddress != null) {
                        pl.detailing.crm.customer.domain.CompanyAddress(
                            street = identity.companyAddress,
                            city = "",
                            postalCode = "",
                            country = ""
                        )
                    } else null
                )
            } else null,
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

    private fun updateCustomer(
        identity: CustomerIdentity.Update,
        studioId: StudioId,
        userId: UserId
    ): CustomerId {
        val entity = customerRepository.findByIdAndStudioId(identity.customerId.value, studioId.value)
            ?: throw EntityNotFoundException("Customer with ID '${identity.customerId}' not found")

        entity.firstName = identity.firstName?.trim()
        entity.lastName = identity.lastName?.trim()
        entity.email = identity.email?.trim()?.lowercase()
        entity.phone = identity.phone?.trim()

        if (identity.companyName != null) {
            entity.companyName = identity.companyName
            entity.companyNip = identity.companyNip
            entity.companyRegon = identity.companyRegon
            if (identity.companyAddress != null) {
                entity.companyAddressStreet = identity.companyAddress
            }
        }

        entity.updatedBy = userId.value
        entity.updatedAt = Instant.now()

        customerRepository.save(entity)

        return identity.customerId
    }

    private fun createVehicle(
        identity: VehicleIdentity.New,
        customerId: CustomerId,
        studioId: StudioId,
        userId: UserId
    ): VehicleId {
        val vehicle = Vehicle(
            id = VehicleId.random(),
            studioId = studioId,
            licensePlate = identity.licensePlate?.trim()?.uppercase(),
            brand = identity.brand.trim(),
            model = identity.model.trim(),
            yearOfProduction = identity.year,
            color = null,
            paintType = null,
            currentMileage = 0,
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

    private fun updateVehicle(
        identity: VehicleIdentity.Update,
        studioId: StudioId,
        userId: UserId
    ): VehicleId {
        val vehicleEntity = vehicleRepository.findByIdAndStudioId(identity.vehicleId.value, studioId.value)
            ?: throw EntityNotFoundException("Vehicle with ID '${identity.vehicleId}' not found")

        vehicleEntity.brand = identity.brand.trim()
        vehicleEntity.model = identity.model.trim()
        vehicleEntity.yearOfProduction = identity.year
        vehicleEntity.licensePlate = identity.licensePlate?.trim()?.uppercase()
        vehicleEntity.updatedBy = userId.value
        vehicleEntity.updatedAt = Instant.now()

        vehicleRepository.save(vehicleEntity)

        return identity.vehicleId
    }
}
