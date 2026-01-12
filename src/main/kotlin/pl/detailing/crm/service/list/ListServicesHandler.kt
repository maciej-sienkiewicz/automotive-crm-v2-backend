package pl.detailing.crm.service.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.StudioId

@Service
class ListServicesHandler(
    private val serviceRepository: ServiceRepository
) {
    suspend fun handle(studioId: StudioId, showInactive: Boolean): List<ServiceListItem> =
        withContext(Dispatchers.IO) {
            val services = if (showInactive) {
                serviceRepository.findAll().filter { it.studioId == studioId.value }
            } else {
                serviceRepository.findActiveByStudioId(studioId.value)
            }

            services.map { entity ->
                ServiceListItem(
                    id = entity.id.toString(),
                    name = entity.name,
                    basePriceNet = entity.basePriceNet,
                    vatRate = entity.vatRate,
                    isActive = entity.isActive,
                    createdAt = entity.createdAt.toString(),
                    updatedAt = entity.updatedAt.toString(),
                    createdBy = entity.createdBy.toString(),
                    updatedBy = entity.updatedBy.toString(),
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
    val createdBy: String,
    val updatedBy: String,
    val replacesServiceId: String?
)