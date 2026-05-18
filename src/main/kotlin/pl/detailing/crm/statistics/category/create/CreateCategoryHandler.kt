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
            throw ValidationException("Nazwa kategorii nie może być pusta")
        }
        if (trimmedName.length > 200) {
            throw ValidationException("Nazwa kategorii nie może przekraczać 200 znaków")
        }
        if (command.color != null && !command.color.matches(Regex("^#[0-9A-Fa-f]{6}$"))) {
            throw ValidationException("Kolor musi być prawidłowym kodem hex, np. #1A2B3C")
        }

        val existing = serviceCategoryRepository.findActiveByStudioIdAndName(
            command.studioId.value, trimmedName
        )
        if (existing != null) {
            throw ValidationException("Kategoria o nazwie '${trimmedName}' już istnieje w tym studiu")
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
