package pl.detailing.crm.customer.consent.create

import jakarta.transaction.Transactional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.consent.domain.ConsentDefinition
import pl.detailing.crm.customer.consent.domain.ConsentTemplate
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionEntity
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateEntity
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateRepository
import pl.detailing.crm.customer.consent.infrastructure.S3ConsentStorageService
import pl.detailing.crm.shared.*
import java.time.Instant

/**
 * Creates a consent definition together with its first template version in a single step.
 *
 * The slug is auto-generated from the name (kebab-case, unique within the studio).
 * Returns a presigned S3 upload URL so the caller can immediately upload the PDF.
 */
@Service
class CreateConsentHandler(
    private val consentDefinitionRepository: ConsentDefinitionRepository,
    private val consentTemplateRepository: ConsentTemplateRepository,
    private val s3StorageService: S3ConsentStorageService
) {

    @Transactional
    suspend fun handle(command: CreateConsentCommand): CreateConsentResult =
        withContext(Dispatchers.IO) {
            val slug = generateUniqueSlug(command.name, command.studioId)

            val definition = ConsentDefinition(
                id = ConsentDefinitionId.random(),
                studioId = command.studioId,
                slug = slug,
                name = command.name.trim(),
                description = command.description?.trim(),
                stage = command.stage,
                isMandatory = command.isMandatory,
                displayOrder = command.displayOrder,
                isActive = true,
                createdBy = command.createdBy,
                updatedBy = command.createdBy,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
            consentDefinitionRepository.save(ConsentDefinitionEntity.fromDomain(definition))

            val s3Key = s3StorageService.buildS3Key(command.studioId.value, slug, 1)
            val uploadUrl = s3StorageService.generateUploadUrl(command.studioId.value, slug, 1)

            val template = ConsentTemplate(
                id = ConsentTemplateId.random(),
                studioId = command.studioId,
                definitionId = definition.id,
                version = 1,
                s3Key = s3Key,
                isActive = true,
                requiresResign = false,
                createdBy = command.createdBy,
                createdAt = Instant.now()
            )
            consentTemplateRepository.save(ConsentTemplateEntity.fromDomain(template))

            CreateConsentResult(
                definitionId = definition.id,
                slug = definition.slug,
                name = definition.name,
                description = definition.description,
                stage = definition.stage,
                isMandatory = definition.isMandatory,
                displayOrder = definition.displayOrder,
                currentVersion = TemplateVersionInfo(
                    versionId = template.id,
                    version = template.version,
                    isActive = template.isActive,
                    requiresResign = template.requiresResign,
                    pdfUploadUrl = uploadUrl,
                    s3Key = s3Key
                )
            )
        }

    private fun generateUniqueSlug(name: String, studioId: StudioId): String {
        val base = name.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .ifEmpty { "consent" }

        if (consentDefinitionRepository.findBySlugAndStudioId(base, studioId.value) == null) return base

        // Append a numeric suffix to ensure uniqueness
        var suffix = 2
        while (true) {
            val candidate = "$base-$suffix"
            if (consentDefinitionRepository.findBySlugAndStudioId(candidate, studioId.value) == null) {
                return candidate
            }
            suffix++
        }
    }
}

data class CreateConsentCommand(
    val studioId: StudioId,
    val createdBy: UserId,
    val name: String,
    val description: String?,
    val stage: ProtocolStage,
    val isMandatory: Boolean,
    val displayOrder: Int
)

data class CreateConsentResult(
    val definitionId: ConsentDefinitionId,
    val slug: String,
    val name: String,
    val description: String?,
    val stage: ProtocolStage,
    val isMandatory: Boolean,
    val displayOrder: Int,
    val currentVersion: TemplateVersionInfo
)

data class TemplateVersionInfo(
    val versionId: ConsentTemplateId,
    val version: Int,
    val isActive: Boolean,
    val requiresResign: Boolean,
    val pdfUploadUrl: String,
    val s3Key: String
)
