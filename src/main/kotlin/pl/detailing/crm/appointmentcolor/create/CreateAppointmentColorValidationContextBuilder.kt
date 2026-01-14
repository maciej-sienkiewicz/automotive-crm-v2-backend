package pl.detailing.crm.appointmentcolor.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository

@Component
class CreateAppointmentColorValidationContextBuilder(
    private val appointmentColorRepository: AppointmentColorRepository
) {
    suspend fun build(command: CreateAppointmentColorCommand): CreateAppointmentColorValidationContext =
        withContext(Dispatchers.IO) {
            val existingColor = appointmentColorRepository.findByStudioIdAndNameIgnoreCase(
                studioId = command.studioId.value,
                name = command.name,
                excludeId = null
            )

            CreateAppointmentColorValidationContext(
                name = command.name,
                hexColor = command.hexColor,
                nameAlreadyExists = existingColor != null
            )
        }
}
