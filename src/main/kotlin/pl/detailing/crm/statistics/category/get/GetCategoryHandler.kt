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
import pl.detailing.crm.statistics.category.manual.ManualServiceCategoryAssignmentRepository
import pl.detailing.crm.statistics.category.manual.ManualServiceRepository
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
    private val serviceRepository: ServiceRepository,
    private val manualServiceRepository: ManualServiceRepository,
    private val manualServiceCategoryAssignmentRepository: ManualServiceCategoryAssignmentRepository
) {
    suspend fun handle(categoryId: ServiceCategoryId, studioId: StudioId): CategoryDetail =
        withContext(Dispatchers.IO) {
            val category = serviceCategoryRepository.findByIdAndStudioId(
                categoryId.value,
                studioId.value
            ) ?: throw EntityNotFoundException("Category $categoryId not found")

            // ── Catalog services ──────────────────────────────────────────────
            val assignments = categoryServiceAssignmentRepository.findByCategoryIdAndStudioId(
                categoryId.value,
                studioId.value
            )

            val allStudioServices = serviceRepository.findByStudioId(studioId.value)
            val servicesThatReplaceOthers: Set<UUID> = allStudioServices
                .mapNotNull { it.replacesServiceId }
                .toSet()
            val allServicesById: Map<UUID, ServiceEntity> = allStudioServices.associateBy { it.id }

            val catalogServices: List<CategoryServiceDetail> = assignments.mapNotNull { assignment ->
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
            }

            // ── Manual services ───────────────────────────────────────────────
            val manualAssignments = manualServiceCategoryAssignmentRepository
                .findByCategoryIdAndStudioId(categoryId.value, studioId.value)

            val manualServiceIds = manualAssignments.map { it.manualServiceId }
            val manualServices: List<CategoryServiceDetail> = if (manualServiceIds.isEmpty()) {
                emptyList()
            } else {
                manualServiceRepository.findAllById(manualServiceIds).map { manual ->
                    CategoryServiceDetail(
                        serviceId = manual.id.toString(),
                        serviceName = manual.serviceName,
                        basePriceNet = 0,
                        vatRate = 0,
                        isActive = true,
                        hasNewerVersion = false
                    )
                }
            }

            val services = (catalogServices + manualServices).sortedBy { it.serviceName }

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

    private fun resolveLatestServiceFromMap(
        rootServiceId: UUID,
        allServicesById: Map<UUID, ServiceEntity>
    ): ServiceEntity? {
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
