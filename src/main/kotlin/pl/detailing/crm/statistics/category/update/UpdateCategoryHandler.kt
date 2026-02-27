package pl.detailing.crm.statistics.category.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.statistics.category.infrastructure.ServiceCategoryRepository
import java.time.Instant

@Service
class UpdateCategoryHandler(
    private val serviceCategoryRepository: ServiceCategoryRepository
) {
    suspend fun handle(command: UpdateCategoryCommand) = withContext(Dispatchers.IO) {
        val entity = serviceCategoryRepository.findByIdAndStudioId(
            command.categoryId.value,
            command.studioId.value
        ) ?: throw EntityNotFoundException("Category ${command.categoryId} not found")

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

        val nameConflict = serviceCategoryRepository.existsActiveByStudioIdAndNameExcludingId(
            command.studioId.value, trimmedName, command.categoryId.value
        )
        if (nameConflict) {
            throw ValidationException("A category with name '${trimmedName}' already exists in this studio")
        }

        entity.name = trimmedName
        entity.description = command.description?.trim()?.takeIf { it.isNotBlank() }
        entity.color = command.color
        entity.updatedAt = Instant.now()

        serviceCategoryRepository.save(entity)
    }
}
