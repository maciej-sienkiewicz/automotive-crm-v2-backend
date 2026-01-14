package pl.detailing.crm.appointmentcolor.update

import jakarta.transaction.Transactional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.shared.AppointmentColorId
import pl.detailing.crm.shared.EntityNotFoundException
import java.time.Instant

@Service
class UpdateAppointmentColorHandler(
    private val validatorComposite: UpdateAppointmentColorValidatorComposite,
    private val appointmentColorRepository: AppointmentColorRepository
) {
    @Transactional
    suspend fun handle(command: UpdateAppointmentColorCommand): UpdateAppointmentColorResult =
        withContext(Dispatchers.IO) {
            validatorComposite.validate(command)

            val entity = appointmentColorRepository.findByIdAndStudioId(
                command.colorId.value,
                command.studioId.value
            ) ?: throw EntityNotFoundException("Appointment color not found")

            entity.name = command.name
            entity.hexColor = command.hexColor
            entity.updatedBy = command.userId.value
            entity.updatedAt = Instant.now()

            appointmentColorRepository.save(entity)

            UpdateAppointmentColorResult(
                colorId = command.colorId,
                name = entity.name,
                hexColor = entity.hexColor
            )
        }
}

data class UpdateAppointmentColorResult(
    val colorId: AppointmentColorId,
    val name: String,
    val hexColor: String
)
