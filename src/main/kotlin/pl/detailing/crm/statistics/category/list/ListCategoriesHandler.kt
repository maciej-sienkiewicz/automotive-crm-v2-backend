package pl.detailing.crm.statistics.category.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.statistics.category.infrastructure.CategoryServiceAssignmentRepository
import pl.detailing.crm.statistics.category.infrastructure.ServiceCategoryRepository
import pl.detailing.crm.statistics.category.manual.ManualServiceCategoryAssignmentRepository
import java.time.Instant
import java.util.UUID

data class CategoryListItem(
    val id: String,
    val name: String,
    val description: String?,
    val color: String?,
    val isActive: Boolean,
    val serviceCount: Long,
    val serviceIds: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant
)

@Service
class ListCategoriesHandler(
    private val serviceCategoryRepository: ServiceCategoryRepository,
    private val categoryServiceAssignmentRepository: CategoryServiceAssignmentRepository,
    private val manualServiceCategoryAssignmentRepository: ManualServiceCategoryAssignmentRepository
) {
    suspend fun handle(studioId: StudioId, includeInactive: Boolean = false): List<CategoryListItem> =
        withContext(Dispatchers.IO) {
            val categories = if (includeInactive) {
                serviceCategoryRepository.findAllByStudioId(studioId.value)
            } else {
                serviceCategoryRepository.findActiveByStudioId(studioId.value)
            }

            // Catalog service assignments (single batch query)
            val catalogRows = categoryServiceAssignmentRepository.findAllServiceIdsByStudio(studioId.value)
            val catalogIdsByCategory: Map<UUID, List<String>> = catalogRows
                .groupBy { it.categoryId }
                .mapValues { (_, v) -> v.map { it.serviceId.toString() } }

            // Manual service assignments (single batch query)
            val manualRows = manualServiceCategoryAssignmentRepository.findByStudioId(studioId.value)
            val manualIdsByCategory: Map<UUID, List<String>> = manualRows
                .groupBy { it.categoryId }
                .mapValues { (_, v) -> v.map { it.manualServiceId.toString() } }

            categories.map { entity ->
                val catalogIds = catalogIdsByCategory[entity.id] ?: emptyList()
                val manualIds = manualIdsByCategory[entity.id] ?: emptyList()
                val allIds = catalogIds + manualIds
                CategoryListItem(
                    id = entity.id.toString(),
                    name = entity.name,
                    description = entity.description,
                    color = entity.color,
                    isActive = entity.isActive,
                    serviceCount = allIds.size.toLong(),
                    serviceIds = allIds,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt
                )
            }
        }
}
