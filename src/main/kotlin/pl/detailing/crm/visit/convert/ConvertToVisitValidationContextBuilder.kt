package pl.detailing.crm.visit.convert

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository

@Component
class ConvertToVisitValidationContextBuilder(
    private val appointmentRepository: AppointmentRepository,
    private val vehicleRepository: VehicleRepository,
    private val customerRepository: CustomerRepository,
    private val visitRepository: VisitRepository
) {
    suspend fun build(command: ConvertToVisitCommand): ConvertToVisitValidationContext =
        withContext(Dispatchers.IO) {
            // Parallel database queries for efficiency
            val appointmentDeferred = async {
                appointmentRepository.findByIdAndStudioId(
                    command.appointmentId.value,
                    command.studioId.value
                )?.toDomain()
            }

            val visitExistsDeferred = async {
                visitRepository.findByAppointmentIdAndStudioId(
                    command.appointmentId.value,
                    command.studioId.value
                ) != null
            }

            val appointment = appointmentDeferred.await()

            // Load vehicle and customer only if appointment exists
            val vehicleDeferred = async {
                appointment?.vehicleId?.let { vehicleId ->
                    vehicleRepository.findByIdAndStudioId(
                        vehicleId.value,
                        command.studioId.value
                    )?.toDomain()
                }
            }

            val customerDeferred = async {
                appointment?.customerId?.let { customerId ->
                    customerRepository.findByIdAndStudioId(
                        customerId.value,
                        command.studioId.value
                    )?.toDomain()
                }
            }

            ConvertToVisitValidationContext(
                studioId = command.studioId,
                appointmentId = command.appointmentId,
                mileageAtArrival = command.mileageAtArrival,
                appointment = appointment,
                vehicle = vehicleDeferred.await(),
                customer = customerDeferred.await(),
                visitAlreadyExists = visitExistsDeferred.await()
            )
        }
}
