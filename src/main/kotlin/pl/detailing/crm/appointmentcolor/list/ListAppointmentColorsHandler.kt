package pl.detailing.crm.appointmentcolor.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.infrastructure.AppointmentColorEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.shared.StudioId

@Service
class ListAppointmentColorsHandler(
    private val appointmentColorRepository: AppointmentColorRepository,
    private val userRepository: UserRepository
) {
    suspend fun handle(studioId: StudioId, showInactive: Boolean): List<AppointmentColorListItem> =
        withContext(Dispatchers.IO) {
            val colors = if (showInactive) {
                appointmentColorRepository.findByStudioId(studioId.value)
            } else {
                appointmentColorRepository.findActiveByStudioId(studioId.value)
            }

            // Kolejność była dotąd zdana na bazę, więc lista potrafiła się przestawić
            // między odświeżeniami. Domyślny na górze, reszta alfabetycznie.
            val ordered = colors.sortedWith(
                compareByDescending<AppointmentColorEntity> { it.isDefault }
                    .thenBy { it.name.lowercase() }
            )

            val userIds = ordered.flatMap { listOf(it.createdBy, it.updatedBy) }.distinct()
            val users = userRepository.findAllById(userIds).associateBy { it.id }

            ordered.map { entity ->
                val createdByUser = users[entity.createdBy]
                val updatedByUser = users[entity.updatedBy]

                AppointmentColorListItem(
                    id = entity.id.toString(),
                    name = entity.name,
                    hexColor = entity.hexColor,
                    isActive = entity.isActive,
                    isDefault = entity.isDefault,
                    createdAt = entity.createdAt.toString(),
                    updatedAt = entity.updatedAt.toString(),
                    createdByFirstName = createdByUser?.firstName ?: "Unknown",
                    createdByLastName = createdByUser?.lastName ?: "User",
                    updatedByFirstName = updatedByUser?.firstName ?: "Unknown",
                    updatedByLastName = updatedByUser?.lastName ?: "User"
                )
            }
        }
}

data class AppointmentColorListItem(
    val id: String,
    val name: String,
    val hexColor: String,
    val isActive: Boolean,
    val isDefault: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val createdByFirstName: String,
    val createdByLastName: String,
    val updatedByFirstName: String,
    val updatedByLastName: String
)
