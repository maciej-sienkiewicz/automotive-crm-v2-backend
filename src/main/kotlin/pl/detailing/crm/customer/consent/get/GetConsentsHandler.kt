package pl.detailing.crm.customer.consent.get

import jakarta.transaction.Transactional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateRepository
import pl.detailing.crm.customer.consent.infrastructure.S3ConsentStorageService
import pl.detailing.crm.shared.ConsentDefinitionId
import pl.detailing.crm.shared.ConsentTemplateId
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.ProtocolStage
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.*

@Service
class GetConsentsHandler(
    private val definitionRepository: ConsentDefinitionRepository,
    private val templateRepository: ConsentTemplateRepository,
    private val s3StorageService: S3ConsentStorageService
) {

    @Transactional
    suspend fun handleList(studioId: StudioId): List<ConsentResponse> =
        withContext(Dispatchers.IO) {
            definitionRepository.findActiveByStudioId(studioId.value)
                .map { def -> toResponse(def.id, studioId) }
        }

    @Transactional
    suspend fun handleGet(studioId: StudioId, definitionId: ConsentDefinitionId): ConsentResponse =
        withContext(Dispatchers.IO) {
            definitionRepository.findByIdAndStudioId(definitionId.value, studioId.value)
                ?: throw NotFoundException("Consent not found")
            toResponse(definitionId.value, studioId)
        }

    private fun toResponse(definitionId: UUID, studioId: StudioId): ConsentResponse {
        val def = definitionRepository.findByIdAndStudioId(definitionId, studioId.value)!!
        val allTemplates = templateRepository.findAllByDefinitionIdAndStudioId(definitionId, studioId.value)
        val activeTemplate = allTemplates.find { it.isActive }

        return ConsentResponse(
            id = def.id,
            slug = def.slug,
            name = def.name,
            description = def.description,
            stage = def.stage,
            isMandatory = def.isMandatory,
            displayOrder = def.displayOrder,
            isActive = def.isActive,
            currentVersion = activeTemplate?.let { t ->
                ConsentVersionResponse(
                    versionId = t.id,
                    version = t.version,
                    isActive = t.isActive,
                    requiresResign = t.requiresResign,
                    pdfUrl = s3StorageService.generateDownloadUrl(t.s3Key),
                    createdAt = t.createdAt
                )
            },
            versions = allTemplates.map { t ->
                ConsentVersionResponse(
                    versionId = t.id,
                    version = t.version,
                    isActive = t.isActive,
                    requiresResign = t.requiresResign,
                    pdfUrl = s3StorageService.generateDownloadUrl(t.s3Key),
                    createdAt = t.createdAt
                )
            },
            createdAt = def.createdAt,
            updatedAt = def.updatedAt
        )
    }
}

data class ConsentResponse(
    val id: UUID,
    val slug: String,
    val name: String,
    val description: String?,
    val stage: ProtocolStage,
    val isMandatory: Boolean,
    val displayOrder: Int,
    val isActive: Boolean,
    val currentVersion: ConsentVersionResponse?,
    val versions: List<ConsentVersionResponse>,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class ConsentVersionResponse(
    val versionId: UUID,
    val version: Int,
    val isActive: Boolean,
    val requiresResign: Boolean,
    val pdfUrl: String,
    val createdAt: Instant
)
