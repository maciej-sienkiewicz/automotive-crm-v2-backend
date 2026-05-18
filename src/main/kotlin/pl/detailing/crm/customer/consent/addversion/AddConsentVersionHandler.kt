package pl.detailing.crm.customer.consent.addversion

import jakarta.transaction.Transactional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.consent.create.TemplateVersionInfo
import pl.detailing.crm.customer.consent.domain.ConsentTemplate
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateEntity
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateRepository
import pl.detailing.crm.customer.consent.infrastructure.S3ConsentStorageService
import pl.detailing.crm.shared.*
import java.time.Instant

/**
 * Publishes a new PDF version for an existing consent definition.
 *
 * When requiresResign is true, customers who already signed an older version
 * will be shown this consent again at their next visit.
 * When setAsActive is true (default), this version becomes the active one.
 */
@Service
class AddConsentVersionHandler(
    private val consentDefinitionRepository: ConsentDefinitionRepository,
    private val consentTemplateRepository: ConsentTemplateRepository,
    private val s3StorageService: S3ConsentStorageService
) {

    @Transactional
    suspend fun handle(command: AddConsentVersionCommand): TemplateVersionInfo =
        withContext(Dispatchers.IO) {
            val definition = consentDefinitionRepository.findByIdAndStudioId(
                command.definitionId.value, command.studioId.value
            ) ?: throw NotFoundException("Zgoda nie została znaleziona")

            if (!definition.isActive) {
                throw ValidationException("Nie można dodać wersji do nieaktywnej zgody")
            }

            val nextVersion = (consentTemplateRepository.findMaxVersionByDefinitionId(
                command.definitionId.value, command.studioId.value
            ) ?: 0) + 1

            val s3Key = s3StorageService.buildS3Key(command.studioId.value, definition.id, nextVersion)
            val uploadUrl = s3StorageService.generateUploadUrl(command.studioId.value, definition.id, nextVersion)

            if (command.setAsActive) {
                consentTemplateRepository.deactivateAllByDefinitionId(command.definitionId.value, command.studioId.value)
            }

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
            consentTemplateRepository.save(ConsentTemplateEntity.fromDomain(template))

            TemplateVersionInfo(
                versionId = template.id,
                version = template.version,
                isActive = template.isActive,
                requiresResign = template.requiresResign,
                pdfUploadUrl = uploadUrl,
                s3Key = s3Key
            )
        }
}

data class AddConsentVersionCommand(
    val studioId: StudioId,
    val createdBy: UserId,
    val definitionId: ConsentDefinitionId,
    val requiresResign: Boolean,
    val setAsActive: Boolean
)
