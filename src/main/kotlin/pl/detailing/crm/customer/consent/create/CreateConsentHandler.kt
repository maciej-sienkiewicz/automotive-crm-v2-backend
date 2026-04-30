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
 * Returns a presigned S3 upload URL so the caller can immediately upload the PDF.
 *
 * Uniqueness rule: at most one active consent per studio may cover a given MarketingChannel.
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
            validateChannelUniqueness(command.studioId, command.marketingChannels, excludeId = null)

            val definition = ConsentDefinition(
                id = ConsentDefinitionId.random(),
                studioId = command.studioId,
                name = command.name.trim(),
                description = command.description?.trim(),
                stage = command.stage,
                marketingChannels = command.marketingChannels,
                displayOrder = command.displayOrder,
                isActive = true,
                createdBy = command.createdBy,
                updatedBy = command.createdBy,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
            consentDefinitionRepository.save(ConsentDefinitionEntity.fromDomain(definition))

            val s3Key = s3StorageService.buildS3Key(command.studioId.value, definition.id.value, 1)
            val uploadUrl = s3StorageService.generateUploadUrl(command.studioId.value, definition.id.value, 1)

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
                name = definition.name,
                description = definition.description,
                stage = definition.stage,
                marketingChannels = definition.marketingChannels,
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

    fun validateChannelUniqueness(
        studioId: StudioId,
        channels: Set<MarketingChannel>,
        excludeId: ConsentDefinitionId?
    ) {
        if (channels.isEmpty()) return
        val active = consentDefinitionRepository.findActiveByStudioId(studioId.value)
        for (channel in channels) {
            val conflict = active.firstOrNull { entity ->
                (excludeId == null || entity.id != excludeId.value) &&
                    channel in entity.marketingChannels
            }
            if (conflict != null) {
                throw ValidationException(
                    "Zgoda '${conflict.name}' już obejmuje kanał ${channel.name}. " +
                    "Tylko jedna aktywna zgoda może dotyczyć danego kanału."
                )
            }
        }
    }
}

data class CreateConsentCommand(
    val studioId: StudioId,
    val createdBy: UserId,
    val name: String,
    val description: String?,
    val stage: ProtocolStage,
    val marketingChannels: Set<MarketingChannel> = emptySet(),
    val displayOrder: Int
)

data class CreateConsentResult(
    val definitionId: ConsentDefinitionId,
    val name: String,
    val description: String?,
    val stage: ProtocolStage,
    val marketingChannels: Set<MarketingChannel>,
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
