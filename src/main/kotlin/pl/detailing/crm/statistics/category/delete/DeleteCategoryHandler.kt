package pl.detailing.crm.statistics.category.delete

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ServiceCategoryId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.statistics.category.infrastructure.ServiceCategoryRepository
import java.time.Instant

@Service
class DeleteCategoryHandler(
    private val serviceCategoryRepository: ServiceCategoryRepository
) {
    /**
     * Soft-deletes a category by marking it inactive.
     * Existing assignments and historical stats are preserved.
     */
    suspend fun handle(categoryId: ServiceCategoryId, studioId: StudioId) = withContext(Dispatchers.IO) {
        val entity = serviceCategoryRepository.findByIdAndStudioId(
            categoryId.value,
            studioId.value
        ) ?: throw EntityNotFoundException("Category $categoryId not found")

        entity.isActive = false
        entity.updatedAt = Instant.now()

        serviceCategoryRepository.save(entity)
    }
}
