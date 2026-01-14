package pl.detailing.crm.appointmentcolor.delete

import jakarta.transaction.Transactional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.shared.EntityNotFoundException

@Service
class DeleteAppointmentColorHandler(
    private val appointmentColorRepository: AppointmentColorRepository
) {
    @Transactional
    suspend fun handle(command: DeleteAppointmentColorCommand) =
        withContext(Dispatchers.IO) {
            val entity = appointmentColorRepository.findByIdAndStudioId(
                command.colorId.value,
                command.studioId.value
            ) ?: throw EntityNotFoundException("Appointment color not found")

            appointmentColorRepository.delete(entity)
        }
}
