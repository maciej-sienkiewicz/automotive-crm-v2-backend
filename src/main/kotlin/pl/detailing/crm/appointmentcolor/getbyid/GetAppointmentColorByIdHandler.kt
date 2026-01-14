package pl.detailing.crm.appointmentcolor.getbyid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.shared.AppointmentColorId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId

@Service
class GetAppointmentColorByIdHandler(
    private val appointmentColorRepository: AppointmentColorRepository,
    private val userRepository: UserRepository
) {
    suspend fun handle(colorId: AppointmentColorId, studioId: StudioId): AppointmentColorResponse =
        withContext(Dispatchers.IO) {
            val entity = appointmentColorRepository.findByIdAndStudioId(colorId.value, studioId.value)
                ?: throw EntityNotFoundException("Appointment color not found")

            val userIds = listOf(entity.createdBy, entity.updatedBy).distinct()
            val users = userRepository.findAllById(userIds).associateBy { it.id }

            val createdByUser = users[entity.createdBy]
            val updatedByUser = users[entity.updatedBy]

            AppointmentColorResponse(
                id = entity.id.toString(),
                name = entity.name,
                hexColor = entity.hexColor,
                isActive = entity.isActive,
                createdAt = entity.createdAt.toString(),
                updatedAt = entity.updatedAt.toString(),
                createdByFirstName = createdByUser?.firstName ?: "Unknown",
                createdByLastName = createdByUser?.lastName ?: "User",
                updatedByFirstName = updatedByUser?.firstName ?: "Unknown",
                updatedByLastName = updatedByUser?.lastName ?: "User"
            )
        }
}

data class AppointmentColorResponse(
    val id: String,
    val name: String,
    val hexColor: String,
    val isActive: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val createdByFirstName: String,
    val createdByLastName: String,
    val updatedByFirstName: String,
    val updatedByLastName: String
)
