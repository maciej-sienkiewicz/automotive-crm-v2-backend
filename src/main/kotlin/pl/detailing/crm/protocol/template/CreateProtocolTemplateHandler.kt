package pl.detailing.crm.protocol.template

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.protocol.domain.ProtocolTemplate
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateEntity
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateRepository
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class CreateProtocolTemplateHandler(
    private val protocolTemplateRepository: ProtocolTemplateRepository,
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

            CreateProtocolTemplateResult(
                template = template,
                uploadUrl = uploadUrl
            )
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
