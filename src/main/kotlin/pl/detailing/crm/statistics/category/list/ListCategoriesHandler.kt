package pl.detailing.crm.statistics.category.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.statistics.category.infrastructure.CategoryServiceAssignmentRepository
import pl.detailing.crm.statistics.category.infrastructure.ServiceCategoryRepository
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
    private val categoryServiceAssignmentRepository: CategoryServiceAssignmentRepository
) {
    suspend fun handle(studioId: StudioId, includeInactive: Boolean = false): List<CategoryListItem> =
        withContext(Dispatchers.IO) {
            val categories = if (includeInactive) {
                serviceCategoryRepository.findAllByStudioId(studioId.value)
            } else {
                serviceCategoryRepository.findActiveByStudioId(studioId.value)
            }

            // Single batch query — avoids N+1 per category
            val rows = categoryServiceAssignmentRepository.findAllServiceIdsByStudio(studioId.value)

            val serviceCounts: Map<UUID, Long> = rows
                .groupBy { it.categoryId }
                .mapValues { (_, v) -> v.size.toLong() }

            val serviceIdsByCategory: Map<UUID, List<String>> = rows
                .groupBy { it.categoryId }
                .mapValues { (_, v) -> v.map { it.serviceId.toString() } }

            categories.map { entity ->
                CategoryListItem(
                    id = entity.id.toString(),
                    name = entity.name,
                    description = entity.description,
                    color = entity.color,
                    isActive = entity.isActive,
                    serviceCount = serviceCounts[entity.id] ?: 0L,
                    serviceIds = serviceIdsByCategory[entity.id] ?: emptyList(),
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt
                )
            }
        }
}
