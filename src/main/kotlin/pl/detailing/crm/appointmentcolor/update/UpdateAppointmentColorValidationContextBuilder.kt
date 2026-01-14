package pl.detailing.crm.appointmentcolor.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository

@Component
class UpdateAppointmentColorValidationContextBuilder(
    private val appointmentColorRepository: AppointmentColorRepository
) {
    suspend fun build(command: UpdateAppointmentColorCommand): UpdateAppointmentColorValidationContext =
        withContext(Dispatchers.IO) {
            val existingColor = appointmentColorRepository.findByIdAndStudioId(
                command.colorId.value,
                command.studioId.value
            )

            val nameConflict = appointmentColorRepository.findByStudioIdAndNameIgnoreCase(
                studioId = command.studioId.value,
                name = command.name,
                excludeId = command.colorId.value
            )

            UpdateAppointmentColorValidationContext(
                name = command.name,
                hexColor = command.hexColor,
                colorExists = existingColor != null,
                nameAlreadyExistsInOtherColor = nameConflict != null
            )
        }
}
