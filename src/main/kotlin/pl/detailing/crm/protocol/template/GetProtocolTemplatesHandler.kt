package pl.detailing.crm.protocol.template

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.protocol.domain.ProtocolTemplate
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateRepository
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.shared.StudioId

@Service
class GetProtocolTemplatesHandler(
    private val protocolTemplateRepository: ProtocolTemplateRepository,
    private val s3StorageService: S3ProtocolStorageService
) {

    suspend fun handle(studioId: StudioId): GetProtocolTemplatesResult = withContext(Dispatchers.IO) {
        val templates = protocolTemplateRepository.findAllByStudioId(studioId.value)
            .map { it.toDomain() }

        GetProtocolTemplatesResult(templates)
    }

    suspend fun handleGetById(studioId: StudioId, templateId: String): GetProtocolTemplateResult =
        withContext(Dispatchers.IO) {
            val template = protocolTemplateRepository.findByIdAndStudioId(
                java.util.UUID.fromString(templateId),
                studioId.value
            )?.toDomain() ?: throw pl.detailing.crm.shared.NotFoundException("Protocol template not found")

            // Generate download URL
            val downloadUrl = s3StorageService.generateDownloadUrl(template.s3Key)

            GetProtocolTemplateResult(template, downloadUrl)
        }
}

data class GetProtocolTemplatesResult(
    val templates: List<ProtocolTemplate>
)

data class GetProtocolTemplateResult(
    val template: ProtocolTemplate,
    val downloadUrl: String
)
