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

            // Single aggregation query — avoids N+1 per category
            val serviceCounts: Map<UUID, Long> = categoryServiceAssignmentRepository
                .countServicesByCategoryForStudio(studioId.value)
                .associate { row -> row.categoryId to row.serviceCount }

            categories.map { entity ->
                CategoryListItem(
                    id = entity.id.toString(),
                    name = entity.name,
                    description = entity.description,
                    color = entity.color,
                    isActive = entity.isActive,
                    serviceCount = serviceCounts[entity.id] ?: 0L,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt
                )
            }
        }
}
