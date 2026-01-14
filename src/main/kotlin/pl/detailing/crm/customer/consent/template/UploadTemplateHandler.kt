package pl.detailing.crm.customer.consent.template

import jakarta.transaction.Transactional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.consent.domain.ConsentTemplate
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateEntity
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateRepository
import pl.detailing.crm.customer.consent.infrastructure.S3ConsentStorageService
import pl.detailing.crm.shared.ConsentTemplateId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant

/**
 * Handler for uploading a new consent template version.
 *
 * This creates a new template record and generates a presigned URL for the frontend
 * to upload the PDF file directly to S3.
 *
 * The version number is automatically incremented based on existing templates.
 * If setAsActive is true, all other templates for this definition are deactivated.
 */
@Service
class UploadTemplateHandler(
    private val consentDefinitionRepository: ConsentDefinitionRepository,
    private val consentTemplateRepository: ConsentTemplateRepository,
    private val s3StorageService: S3ConsentStorageService
) {

    @Transactional
    suspend fun handle(command: UploadTemplateCommand): UploadTemplateResult =
        withContext(Dispatchers.IO) {
            // Step 1: Validate definition exists
            val definition = consentDefinitionRepository.findByIdAndStudioId(
                command.definitionId.value,
                command.studioId.value
            ) ?: throw ValidationException(
                "Consent definition with ID '${command.definitionId}' does not exist or is not accessible"
            )

            if (!definition.isActive) {
                throw ValidationException(
                    "Cannot create template for inactive definition '${definition.name}'"
                )
            }

            // Step 2: Calculate next version number
            val maxVersion = consentTemplateRepository.findMaxVersionByDefinitionId(
                command.definitionId.value,
                command.studioId.value
            ) ?: 0
            val nextVersion = maxVersion + 1

            // Step 3: Generate S3 key and presigned upload URL
            val s3Key = s3StorageService.buildS3Key(
                studioId = command.studioId.value,
                definitionSlug = definition.slug,
                version = nextVersion
            )

            val uploadUrl = s3StorageService.generateUploadUrl(
                studioId = command.studioId.value,
                definitionSlug = definition.slug,
                version = nextVersion
            )

            // Step 4: If setAsActive, deactivate all other templates for this definition
            if (command.setAsActive) {
                consentTemplateRepository.deactivateAllByDefinitionId(
                    command.definitionId.value,
                    command.studioId.value
                )
            }

            // Step 5: Create new template record
            val template = ConsentTemplate(
                id = ConsentTemplateId.random(),
                studioId = command.studioId,
                definitionId = command.definitionId,
                version = nextVersion,
                s3Key = s3Key,
                isActive = command.setAsActive,
                requiresResign = command.requiresResign,
                createdBy = command.createdBy,
                createdAt = Instant.now()
            )

            // Step 6: Persist
            val entity = ConsentTemplateEntity.fromDomain(template)
            consentTemplateRepository.save(entity)

            // Step 7: Return result with upload URL
            UploadTemplateResult(
                templateId = template.id,
                version = template.version,
                uploadUrl = uploadUrl,
                s3Key = s3Key
            )
        }
}
