package pl.detailing.crm.customer.consent.getdefinitions

import java.time.Instant
import java.util.*

/**
 * Result containing all consent definitions with their templates.
 */
data class GetAllDefinitionsResult(
    val definitions: List<DefinitionWithTemplates>
)

/**
 * A consent definition with its active template and all historical templates.
 * Matches the frontend ConsentDefinitionWithTemplate interface.
 */
data class DefinitionWithTemplates(
    val definition: DefinitionDto,
    val activeTemplate: TemplateDto?,
    val allTemplates: List<TemplateDto>
)

/**
 * Consent definition DTO.
 * Matches the frontend ConsentDefinition interface.
 */
data class DefinitionDto(
    val id: UUID,
    val slug: String,
    val name: String,
    val description: String?,
    val createdAt: Instant,
    val createdBy: UUID,
    val studioId: UUID
)

/**
 * Consent template DTO.
 * Matches the frontend ConsentTemplate interface.
 */
data class TemplateDto(
    val id: UUID,
    val definitionId: UUID,
    val version: Int,
    val requiresResign: Boolean,
    val isActive: Boolean,
    val s3Key: String,
    val createdAt: Instant,
    val createdBy: UUID
)
