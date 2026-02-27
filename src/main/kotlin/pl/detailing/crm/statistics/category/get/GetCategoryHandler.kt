package pl.detailing.crm.statistics.category.get

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ServiceCategoryId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.statistics.category.infrastructure.CategoryServiceAssignmentRepository
import pl.detailing.crm.statistics.category.infrastructure.ServiceCategoryRepository
import java.time.Instant
import java.util.UUID

data class CategoryServiceDetail(
    val serviceId: String,
    val serviceName: String,
    val basePriceNet: Long,
    val vatRate: Int,
    val isActive: Boolean,
    val hasNewerVersion: Boolean
)

data class CategoryDetail(
    val id: String,
    val name: String,
    val description: String?,
    val color: String?,
    val isActive: Boolean,
    val services: List<CategoryServiceDetail>,
    val createdAt: Instant,
    val updatedAt: Instant
)

@Service
class GetCategoryHandler(
    private val serviceCategoryRepository: ServiceCategoryRepository,
    private val categoryServiceAssignmentRepository: CategoryServiceAssignmentRepository,
    private val serviceRepository: ServiceRepository
) {
    suspend fun handle(categoryId: ServiceCategoryId, studioId: StudioId): CategoryDetail =
        withContext(Dispatchers.IO) {
            val category = serviceCategoryRepository.findByIdAndStudioId(
                categoryId.value,
                studioId.value
            ) ?: throw EntityNotFoundException("Category $categoryId not found")

            val assignments = categoryServiceAssignmentRepository.findByCategoryIdAndStudioId(
                categoryId.value,
                studioId.value
            )

            // Resolve all services in the studio to build a map for "hasNewerVersion" detection.
            // A service has a newer version if another service has replaces_service_id = this service's id.
            val allStudioServices = serviceRepository.findByStudioId(studioId.value)
            val servicesThatReplaceOthers: Set<UUID> = allStudioServices
                .mapNotNull { it.replacesServiceId }
                .toSet()

            // Build a lookup map to avoid repeated DB calls during version traversal
            val allServicesById: Map<UUID, ServiceEntity> = allStudioServices.associateBy { it.id }

            val services: List<CategoryServiceDetail> = assignments.mapNotNull { assignment ->
                // Resolve root to tip of version chain using the in-memory map (no extra DB calls)
                val latestService = resolveLatestServiceFromMap(assignment.serviceId, allServicesById)
                latestService?.let { svc ->
                    CategoryServiceDetail(
                        serviceId = svc.id.toString(),
                        serviceName = svc.name,
                        basePriceNet = svc.basePriceNet,
                        vatRate = svc.vatRate,
                        isActive = svc.isActive,
                        hasNewerVersion = servicesThatReplaceOthers.contains(svc.id)
                    )
                }
            }.sortedBy { it.serviceName }

            CategoryDetail(
                id = category.id.toString(),
                name = category.name,
                description = category.description,
                color = category.color,
                isActive = category.isActive,
                services = services,
                createdAt = category.createdAt,
                updatedAt = category.updatedAt
            )
        }

    /**
     * Resolves a root service ID to the tip of the version chain using an in-memory map.
     * Avoids N+1 DB calls — all studio services are loaded once and reused.
     *
     * Builds a reverse lookup: for each service, find the one that replaced it.
     * Traverses forward until no newer version exists.
     */
    private fun resolveLatestServiceFromMap(
        rootServiceId: UUID,
        allServicesById: Map<UUID, ServiceEntity>
    ): ServiceEntity? {
        // Build reverse index: id -> the service that replaced it
        val replacedBy: Map<UUID, ServiceEntity> = allServicesById.values
            .filter { it.replacesServiceId != null }
            .associateBy { it.replacesServiceId!! }

        var current = allServicesById[rootServiceId] ?: return null
        while (true) {
            val next = replacedBy[current.id]
            current = next ?: return current
        }
    }
}
