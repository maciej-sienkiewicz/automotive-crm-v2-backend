package pl.detailing.crm.appointment.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.domain.*
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
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
class CreateAppointmentHandler(
    private val validatorComposite: CreateAppointmentValidatorComposite,
    private val appointmentRepository: AppointmentRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val serviceRepository: ServiceRepository
) {

    @Transactional
    suspend fun handle(command: CreateAppointmentCommand): CreateAppointmentResult = withContext(Dispatchers.IO) {
        // Step 1: Validation
        validatorComposite.validate(command)

        // Step 2: Identity Resolution - Customer
        val customerId = when (val identity = command.customer) {
            is CustomerIdentity.Existing -> identity.customerId
            is CustomerIdentity.New -> createCustomer(identity, command.studioId, command.userId)
        }

        // Step 3: Identity Resolution - Vehicle
        val vehicleId = when (val identity = command.vehicle) {
            is VehicleIdentity.Existing -> identity.vehicleId
            is VehicleIdentity.New -> createVehicle(identity, customerId, command.studioId, command.userId)
            VehicleIdentity.None -> null
        }

        // Step 4: Fetch Services and Create Line Items
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

        // Step 5: Create Appointment Domain Object
        val appointment = Appointment(
            id = AppointmentId.random(),
            studioId = command.studioId,
            customerId = customerId,
            vehicleId = vehicleId,
            appointmentTitle = command.appointmentTitle,
            appointmentColorId = command.appointmentColorId,
            lineItems = lineItems,
            schedule = AppointmentSchedule(
                isAllDay = command.schedule.isAllDay,
                startDateTime = command.schedule.startDateTime,
                endDateTime = command.schedule.endDateTime
            ),
            status = AppointmentStatus.CREATED,
            createdBy = command.userId,
            updatedBy = command.userId,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        // Step 6: Persist Appointment
        val appointmentEntity = AppointmentEntity.fromDomain(appointment)
        appointmentRepository.save(appointmentEntity)

        CreateAppointmentResult(
            appointmentId = appointment.id,
            customerId = customerId,
            vehicleId = vehicleId,
            totalNet = appointment.calculateTotalNet(),
            totalGross = appointment.calculateTotalGross(),
            totalVat = appointment.calculateTotalVat()
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
            firstName = identity.firstName.trim(),
            lastName = identity.lastName.trim(),
            email = identity.email.trim().lowercase(),
            phone = identity.phone.trim(),
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

    private fun createVehicle(
        identity: VehicleIdentity.New,
        customerId: CustomerId,
        studioId: StudioId,
        userId: UserId
    ): VehicleId {
        val vehicle = Vehicle(
            id = VehicleId.random(),
            studioId = studioId,
            licensePlate = identity.licensePlate?.trim()?.uppercase() ?: "UNKNOWN",
            brand = identity.brand.trim(),
            model = identity.model.trim(),
            yearOfProduction = identity.year ?: 2000,
            color = null,
            paintType = null,
            engineType = EngineType.GASOLINE,
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
}

data class CreateAppointmentResult(
    val appointmentId: AppointmentId,
    val customerId: CustomerId,
    val vehicleId: VehicleId?,
    val totalNet: Money,
    val totalGross: Money,
    val totalVat: Money
)
