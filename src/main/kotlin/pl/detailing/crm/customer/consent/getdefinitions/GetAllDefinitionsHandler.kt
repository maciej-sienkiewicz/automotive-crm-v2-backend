package pl.detailing.crm.customer.consent.getdefinitions

import jakarta.transaction.Transactional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateRepository
import pl.detailing.crm.shared.StudioId
import java.util.*

/**
 * Handler for retrieving all consent definitions with their templates.
 * Returns all definitions for a studio along with their active and historical templates.
 */
@Component
class GetAllDefinitionsHandler(
    private val definitionRepository: ConsentDefinitionRepository,
    private val templateRepository: ConsentTemplateRepository
) {

    @Transactional
    suspend fun handle(command: GetAllDefinitionsCommand): GetAllDefinitionsResult =
        withContext(Dispatchers.IO) {
            // Get all active definitions for the studio
            val definitions = definitionRepository.findActiveByStudioId(command.studioId.value)

            // For each definition, get all templates
            val definitionsWithTemplates = definitions.map { definitionEntity ->
                val allTemplates = templateRepository.findAllByDefinitionIdAndStudioId(
                    definitionId = definitionEntity.id,
                    studioId = command.studioId.value
                )

                val activeTemplate = allTemplates.find { it.isActive }

                DefinitionWithTemplates(
                    definition = DefinitionDto(
                        id = definitionEntity.id,
                        slug = definitionEntity.slug,
                        name = definitionEntity.name,
                        description = definitionEntity.description,
                        createdAt = definitionEntity.createdAt,
                        createdBy = definitionEntity.createdBy,
                        studioId = definitionEntity.studioId
                    ),
                    activeTemplate = activeTemplate?.let {
                        TemplateDto(
                            id = it.id,
                            definitionId = it.definitionId,
                            version = it.version,
                            requiresResign = it.requiresResign,
                            isActive = it.isActive,
                            s3Key = it.s3Key,
                            createdAt = it.createdAt,
                            createdBy = it.createdBy
                        )
                    },
                    allTemplates = allTemplates.map { templateEntity ->
                        TemplateDto(
                            id = templateEntity.id,
                            definitionId = templateEntity.definitionId,
                            version = templateEntity.version,
                            requiresResign = templateEntity.requiresResign,
                            isActive = templateEntity.isActive,
                            s3Key = templateEntity.s3Key,
                            createdAt = templateEntity.createdAt,
                            createdBy = templateEntity.createdBy
                        )
                    }
                )
            }

            GetAllDefinitionsResult(definitionsWithTemplates)
        }
}
