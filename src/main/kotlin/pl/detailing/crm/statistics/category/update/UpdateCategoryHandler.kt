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
        ) ?: throw EntityNotFoundException("Kategoria ${command.categoryId} nie została znaleziona")

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

        val nameConflict = serviceCategoryRepository.existsActiveByStudioIdAndNameExcludingId(
            command.studioId.value, trimmedName, command.categoryId.value
        )
        if (nameConflict) {
            throw ValidationException("Kategoria o nazwie '${trimmedName}' już istnieje w tym studiu")
        }

        entity.name = trimmedName
        entity.description = command.description?.trim()?.takeIf { it.isNotBlank() }
        entity.color = command.color
        entity.updatedAt = Instant.now()

        serviceCategoryRepository.save(entity)
    }
}
