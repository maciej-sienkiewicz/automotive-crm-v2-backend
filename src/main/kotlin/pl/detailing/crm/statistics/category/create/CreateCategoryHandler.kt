package pl.detailing.crm.statistics.category.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.ServiceCategoryId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.statistics.category.infrastructure.ServiceCategoryEntity
import pl.detailing.crm.statistics.category.infrastructure.ServiceCategoryRepository
import java.time.Instant

@Service
class CreateCategoryHandler(
    private val serviceCategoryRepository: ServiceCategoryRepository
) {
    suspend fun handle(command: CreateCategoryCommand): ServiceCategoryId = withContext(Dispatchers.IO) {
        val trimmedName = command.name.trim()

        if (trimmedName.isBlank()) {
            throw ValidationException("Category name cannot be blank")
        }
        if (trimmedName.length > 200) {
            throw ValidationException("Category name cannot exceed 200 characters")
        }
        if (command.color != null && !command.color.matches(Regex("^#[0-9A-Fa-f]{6}$"))) {
            throw ValidationException("Color must be a valid hex code, e.g. #1A2B3C")
        }

        val existing = serviceCategoryRepository.findActiveByStudioIdAndName(
            command.studioId.value, trimmedName
        )
        if (existing != null) {
            throw ValidationException("A category with name '${trimmedName}' already exists in this studio")
        }

        val now = Instant.now()
        val categoryId = ServiceCategoryId.random()

        val entity = ServiceCategoryEntity(
            id = categoryId.value,
            studioId = command.studioId.value,
            name = trimmedName,
            description = command.description?.trim()?.takeIf { it.isNotBlank() },
            color = command.color,
            isActive = true,
            createdBy = command.createdBy.value,
            createdAt = now,
            updatedAt = now
        )

        serviceCategoryRepository.save(entity)

        categoryId
    }
}
