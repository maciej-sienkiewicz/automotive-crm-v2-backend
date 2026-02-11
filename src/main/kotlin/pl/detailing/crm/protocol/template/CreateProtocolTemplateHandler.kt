package pl.detailing.crm.protocol.template

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.protocol.domain.ProtocolFieldMapping
import pl.detailing.crm.protocol.domain.ProtocolTemplate
import pl.detailing.crm.protocol.infrastructure.ProtocolFieldMappingEntity
import pl.detailing.crm.protocol.infrastructure.ProtocolFieldMappingRepository
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateEntity
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateRepository
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class CreateProtocolTemplateHandler(
    private val protocolTemplateRepository: ProtocolTemplateRepository,
    private val protocolFieldMappingRepository: ProtocolFieldMappingRepository,
    private val s3StorageService: S3ProtocolStorageService
) {

    @Transactional
    suspend fun handle(command: CreateProtocolTemplateCommand): CreateProtocolTemplateResult =
        withContext(Dispatchers.IO) {
            // Check if template with same name already exists
            val exists = protocolTemplateRepository.existsActiveByStudioIdAndName(
                command.studioId.value,
                command.name
            )
            if (exists) {
                throw ValidationException("Protocol template with name '${command.name}' already exists")
            }

            val templateId = ProtocolTemplateId.random()

            // Generate S3 upload URL
            val s3Key = s3StorageService.buildTemplateS3Key(command.studioId.value, templateId.value)
            val uploadUrl = s3StorageService.generateTemplateUploadUrl(
                command.studioId.value,
                templateId.value
            )

            // Create template
            val template = ProtocolTemplate(
                id = templateId,
                studioId = command.studioId,
                name = command.name.trim(),
                description = command.description?.trim(),
                s3Key = s3Key,
                isActive = true,
                createdBy = command.userId,
                updatedBy = command.userId,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )

            val entity = ProtocolTemplateEntity.fromDomain(template)
            protocolTemplateRepository.save(entity)

            // Create default field mappings
            createDefaultFieldMappings(template)

            CreateProtocolTemplateResult(
                template = template,
                uploadUrl = uploadUrl
            )
        }

    /**
     * Creates default field mappings for the template.
     * This ensures that newly created templates have a standard set of field mappings
     * that can be used immediately or customized by the user later.
     */
    private fun createDefaultFieldMappings(template: ProtocolTemplate) {
        val defaultMappings = DefaultProtocolFieldMappings.getDefaultMappings()
        val now = Instant.now()

        val mappingEntities = defaultMappings.map { (pdfFieldName, crmDataKey) ->
            val mapping = ProtocolFieldMapping(
                id = ProtocolFieldMappingId.random(),
                studioId = template.studioId,
                templateId = template.id,
                pdfFieldName = pdfFieldName,
                crmDataKey = crmDataKey,
                createdAt = now
            )
            ProtocolFieldMappingEntity.fromDomain(mapping)
        }

        protocolFieldMappingRepository.saveAll(mappingEntities)
    }
}

data class CreateProtocolTemplateCommand(
    val studioId: StudioId,
    val userId: UserId,
    val name: String,
    val description: String?
)

data class CreateProtocolTemplateResult(
    val template: ProtocolTemplate,
    val uploadUrl: String
)
