package pl.detailing.crm.appointment.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import pl.detailing.crm.appointment.domain.AppointmentSchedule
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.service.domain.Service
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.shared.StudioId

@Component
class CreateAppointmentValidationContextBuilder(
    private val serviceRepository: ServiceRepository,
    private val appointmentRepository: AppointmentRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository,
    private val appointmentColorRepository: AppointmentColorRepository
) {
    suspend fun build(command: CreateAppointmentCommand): CreateAppointmentValidationContext =
        withContext(Dispatchers.IO) {
            // Parallel async queries for all validation data
            val servicesDeferred = async {
                val serviceIds = command.services.map { it.serviceId.value }
                serviceRepository.findActiveByStudioId(command.studioId.value)
                    .filter { it.id in serviceIds }
                    .map { it.toDomain() }
            }

            val overlappingAppointmentsDeferred = async {
                appointmentRepository.findOverlappingAppointments(
                    command.studioId.value,
                    command.schedule.startDateTime,
                    command.schedule.endDateTime
                )
            }

            val existingCustomerDeferred = async {
                when (val identity = command.customer) {
                    is CustomerIdentity.Existing -> {
                        customerRepository.findByIdAndStudioId(
                            identity.customerId.value,
                            command.studioId.value
                        )
                    }
                    is CustomerIdentity.Update -> {
                        customerRepository.findByIdAndStudioId(
                            identity.customerId.value,
                            command.studioId.value
                        )
                    }
                    is CustomerIdentity.New -> null
                }
            }

            val existingVehicleDeferred = async {
                when (val identity = command.vehicle) {
                    is VehicleIdentity.Existing -> {
                        vehicleRepository.findByIdAndStudioId(
                            identity.vehicleId.value,
                            command.studioId.value
                        )
                    }
                    is VehicleIdentity.Update -> {
                        vehicleRepository.findByIdAndStudioId(
                            identity.vehicleId.value,
                            command.studioId.value
                        )
                    }
                    is VehicleIdentity.New, VehicleIdentity.None -> null
                }
            }

            val appointmentColorExistsDeferred = async {
                appointmentColorRepository.findByIdAndStudioId(
                    command.appointmentColorId.value,
                    command.studioId.value
                ) != null
            }

            // Check email/phone uniqueness for new customers
            val emailExistsDeferred = async {
                when (val identity = command.customer) {
                    is CustomerIdentity.New -> {
                        customerRepository.existsActiveByStudioIdAndEmail(
                            command.studioId.value,
                            identity.email.trim().lowercase()
                        )
                    }
                    is CustomerIdentity.Update -> {
                        val existing = customerRepository.findActiveByStudioIdAndEmail(
                            command.studioId.value,
                            identity.email.trim().lowercase()
                        )
                        existing != null && existing.id != identity.customerId.value
                    }
                    is CustomerIdentity.Existing -> false
                }
            }

            val phoneExistsDeferred = async {
                when (val identity = command.customer) {
                    is CustomerIdentity.New -> {
                        customerRepository.existsActiveByStudioIdAndPhone(
                            command.studioId.value,
                            identity.phone.trim()
                        )
                    }
                    is CustomerIdentity.Update -> {
                        val existing = customerRepository.findActiveByStudioIdAndPhone(
                            command.studioId.value,
                            identity.phone.trim()
                        )
                        existing != null && existing.id != identity.customerId.value
                    }
                    is CustomerIdentity.Existing -> false
                }
            }

            CreateAppointmentValidationContext(
                studioId = command.studioId,
                services = servicesDeferred.await(),
                overlappingAppointments = overlappingAppointmentsDeferred.await(),
                existingCustomer = existingCustomerDeferred.await(),
                existingVehicle = existingVehicleDeferred.await(),
                appointmentColorExists = appointmentColorExistsDeferred.await(),
                customerEmailExists = emailExistsDeferred.await(),
                customerPhoneExists = phoneExistsDeferred.await(),
                requestedServiceIds = command.services.map { it.serviceId },
                requestedServiceLineItems = command.services,
                schedule = AppointmentSchedule(
                    isAllDay = command.schedule.isAllDay,
                    startDateTime = command.schedule.startDateTime,
                    endDateTime = command.schedule.endDateTime
                ),
                customerIdentity = command.customer,
                vehicleIdentity = command.vehicle
            )
        }
}

data class CreateAppointmentValidationContext(
    val studioId: StudioId,
    val services: List<Service>,
    val overlappingAppointments: List<AppointmentEntity>,
    val existingCustomer: pl.detailing.crm.customer.infrastructure.CustomerEntity?,
    val existingVehicle: pl.detailing.crm.vehicle.infrastructure.VehicleEntity?,
    val appointmentColorExists: Boolean,
    val customerEmailExists: Boolean,
    val customerPhoneExists: Boolean,
    val requestedServiceIds: List<pl.detailing.crm.shared.ServiceId>,
    val requestedServiceLineItems: List<ServiceLineItemCommand>,
    val schedule: AppointmentSchedule,
    val customerIdentity: CustomerIdentity,
    val vehicleIdentity: VehicleIdentity
)
