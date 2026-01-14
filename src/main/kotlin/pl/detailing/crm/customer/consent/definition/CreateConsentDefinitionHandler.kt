package pl.detailing.crm.customer.consent.definition

import jakarta.transaction.Transactional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.consent.domain.ConsentDefinition
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionEntity
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.shared.ConsentDefinitionId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant

/**
 * Handler for creating a new consent definition.
 *
 * Validates that the slug is unique within the studio and creates the definition.
 * After creating a definition, admin can upload PDF templates for it.
 */
@Service
class CreateConsentDefinitionHandler(
    private val consentDefinitionRepository: ConsentDefinitionRepository
) {

    @Transactional
    suspend fun handle(command: CreateConsentDefinitionCommand): CreateConsentDefinitionResult =
        withContext(Dispatchers.IO) {
            // Step 1: Validate slug uniqueness
            val slugExists = consentDefinitionRepository.existsBySlugAndStudioId(
                command.slug.trim().lowercase(),
                command.studioId.value
            )

            if (slugExists) {
                throw ValidationException(
                    "Consent definition with slug '${command.slug}' already exists in this studio"
                )
            }

            // Step 2: Validate slug format (alphanumeric and dashes only)
            val slugRegex = Regex("^[a-z0-9-]+$")
            if (!command.slug.trim().lowercase().matches(slugRegex)) {
                throw ValidationException(
                    "Slug must contain only lowercase letters, numbers, and dashes"
                )
            }

            // Step 3: Create domain object
            val definition = ConsentDefinition(
                id = ConsentDefinitionId.random(),
                studioId = command.studioId,
                slug = command.slug.trim().lowercase(),
                name = command.name.trim(),
                description = command.description?.trim(),
                isActive = true,
                createdBy = command.createdBy,
                updatedBy = command.createdBy,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )

            // Step 4: Persist
            val entity = ConsentDefinitionEntity.fromDomain(definition)
            consentDefinitionRepository.save(entity)

            // Step 5: Return result
            CreateConsentDefinitionResult(
                definitionId = definition.id,
                slug = definition.slug,
                name = definition.name
            )
        }
}
