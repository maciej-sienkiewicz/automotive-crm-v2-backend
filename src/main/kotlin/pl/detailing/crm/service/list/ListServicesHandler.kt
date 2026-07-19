package pl.detailing.crm.service.list

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.service.infrastructure.ServicePackageItemRepository
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.shared.StudioId

@Service
class ListServicesHandler(
    private val serviceRepository: ServiceRepository,
    private val packageItemRepository: ServicePackageItemRepository,
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

            val packageIds = services.filter { it.isPackage }.map { it.id }
            val packageItemsByPackageId = if (packageIds.isNotEmpty()) {
                packageItemRepository.findByPackageIdIn(packageIds)
                    .groupBy { it.packageId }
            } else {
                emptyMap()
            }

            services.map { entity ->
                val createdByUser = users[entity.createdBy]
                val updatedByUser = users[entity.updatedBy]
                val packageItems = if (entity.isPackage) {
                    packageItemsByPackageId[entity.id]
                        ?.sortedBy { it.position }
                        ?.map { PackageItemDto(serviceId = it.serviceId.toString(), serviceName = it.serviceName, position = it.position) }
                        ?: emptyList()
                } else null

                ServiceListItem(
                    id = entity.id.toString(),
                    name = entity.name,
                    basePriceNet = entity.basePriceNet,
                    basePriceGross = entity.basePriceGross,
                    vatRate = entity.vatRate,
                    isActive = entity.isActive,
                    requireManualPrice = entity.requireManualPrice,
                    isPackage = entity.isPackage,
                    packageItems = packageItems,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt,
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
    val basePriceGross: Long,
    val vatRate: Int,
    val isActive: Boolean,
    val requireManualPrice: Boolean,
    val isPackage: Boolean,
    val packageItems: List<PackageItemDto>?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdByFirstName: String,
    val createdByLastName: String,
    val updatedByFirstName: String,
    val updatedByLastName: String,
    val replacesServiceId: String?
)

data class PackageItemDto(
    val serviceId: String,
    val serviceName: String,
    val position: Int
)