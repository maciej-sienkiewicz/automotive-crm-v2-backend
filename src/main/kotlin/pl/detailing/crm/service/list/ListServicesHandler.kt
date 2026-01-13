package pl.detailing.crm.service.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.shared.StudioId

@Service
class ListServicesHandler(
    private val serviceRepository: ServiceRepository,
    private val userRepository: UserRepository
) {
    suspend fun handle(studioId: StudioId, showInactive: Boolean): List<ServiceListItem> =
        withContext(Dispatchers.IO) {
            val services = if (showInactive) {
                serviceRepository.findByStudioId(studioId.value)
            } else {
                serviceRepository.findActiveByStudioId(studioId.value)
            }

            val userIds = services.flatMap { listOf(it.createdBy, it.updatedBy) }.distinct()

            val users = userRepository.findAllById(userIds).associateBy { it.id }

            services.map { entity ->
                val createdByUser = users[entity.createdBy]
                val updatedByUser = users[entity.updatedBy]

                ServiceListItem(
                    id = entity.id.toString(),
                    name = entity.name,
                    basePriceNet = entity.basePriceNet,
                    vatRate = entity.vatRate,
                    isActive = entity.isActive,
                    createdAt = entity.createdAt.toString(),
                    updatedAt = entity.updatedAt.toString(),
                    createdByFirstName = createdByUser?.firstName ?: "Unknown",
                    createdByLastName = createdByUser?.lastName ?: "User",
                    updatedByFirstName = updatedByUser?.firstName ?: "Unknown",
                    updatedByLastName = updatedByUser?.lastName ?: "User",
                    replacesServiceId = entity.replacesServiceId?.toString(),
                )
            }
        }
}

data class ServiceListItem(
    val id: String,
    val name: String,
    val basePriceNet: Long,
    val vatRate: Int,
    val isActive: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val createdByFirstName: String,
    val createdByLastName: String,
    val updatedByFirstName: String,
    val updatedByLastName: String,
    val replacesServiceId: String?
)