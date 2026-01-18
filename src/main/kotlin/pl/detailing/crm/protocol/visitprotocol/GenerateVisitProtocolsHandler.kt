package pl.detailing.crm.protocol.visitprotocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.protocol.domain.VisitProtocol
import pl.detailing.crm.protocol.infrastructure.*
import pl.detailing.crm.shared.*
import java.time.Instant

/**
 * Handler for generating protocol instances for a visit.
 *
 * This creates VisitProtocol entities based on the resolved rules for the visit.
 * Protocols are created in PENDING status and will be filled and signed later.
 */
@Service
class GenerateVisitProtocolsHandler(
    private val protocolResolver: ProtocolResolver,
    private val visitProtocolRepository: VisitProtocolRepository,
    private val crmDataResolver: CrmDataResolver,
    private val pdfProcessingService: PdfProcessingService,
    private val s3StorageService: S3ProtocolStorageService,
    private val protocolFieldMappingRepository: ProtocolFieldMappingRepository
) {

    @Transactional
    suspend fun handle(command: GenerateVisitProtocolsCommand): GenerateVisitProtocolsResult =
        withContext(Dispatchers.IO) {
            // Check if protocols already exist for this visit and stage
            val existingProtocols = visitProtocolRepository.findAllByVisitIdAndStudioIdAndStage(
                command.visitId.value,
                command.studioId.value,
                command.stage
            )

            if (existingProtocols.isNotEmpty()) {
                // Protocols already exist, return them
                return@withContext GenerateVisitProtocolsResult(
                    protocols = existingProtocols.map { it.toDomain() }
                )
            }

            // Resolve required protocols for this visit
            val requiredRules = protocolResolver.resolveRequiredProtocols(
                command.visitId,
                command.studioId,
                command.stage
            )

            // Create visit protocol instances
            val visitProtocols = requiredRules.map { rule ->
                val visitProtocol = VisitProtocol(
                    id = VisitProtocolId.random(),
                    studioId = command.studioId,
                    visitId = command.visitId,
                    templateId = rule.templateId,
                    stage = command.stage,
                    isMandatory = rule.isMandatory,
                    status = VisitProtocolStatus.PENDING,
                    filledPdfS3Key = null,
                    signedPdfS3Key = null,
                    signedAt = null,
                    signedBy = null,
                    signatureImageS3Key = null,
                    notes = null,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now()
                )

                val entity = VisitProtocolEntity.fromDomain(visitProtocol)
                visitProtocolRepository.save(entity)

                // Automatically fill the PDF with CRM data
                fillProtocolPdf(visitProtocol, command.studioId)

                // Reload to get updated status
                visitProtocolRepository.findById(visitProtocol.id.value).get().toDomain()
            }

            GenerateVisitProtocolsResult(visitProtocols)
        }

    private suspend fun fillProtocolPdf(visitProtocol: VisitProtocol, studioId: StudioId): VisitProtocol {
        return try {
            // Resolve CRM data for the visit
            val crmData = crmDataResolver.resolveVisitData(visitProtocol.visitId, studioId)

            // Get field mappings for this template
            val fieldMappings = protocolFieldMappingRepository.findAllByTemplateIdAndStudioId(
                visitProtocol.templateId.value,
                studioId.value
            )

            // Build field name to value map
            val fieldValues = fieldMappings.associate { mapping ->
                val value = crmData[mapping.crmDataKey] ?: ""
                mapping.pdfFieldName to value
            }

            // Get template S3 key
            val templateS3Key = s3StorageService.buildTemplateS3Key(
                studioId.value,
                visitProtocol.templateId.value
            )

            // Build output S3 key for filled PDF
            val filledPdfS3Key = s3StorageService.buildFilledPdfS3Key(
                studioId.value,
                visitProtocol.visitId.value,
                visitProtocol.id.value
            )

            // Fill the PDF
            pdfProcessingService.fillPdfForm(templateS3Key, fieldValues, filledPdfS3Key)

            // Update protocol status
            val updated = visitProtocol.markAsReadyForSignature(filledPdfS3Key)
            val entity = VisitProtocolEntity.fromDomain(updated)
            visitProtocolRepository.save(entity)

            updated
        } catch (e: Exception) {
            // Log error but don't fail the entire generation
            println("Failed to fill PDF for protocol ${visitProtocol.id}: ${e.message}")
            visitProtocol
        }
    }
}

data class GenerateVisitProtocolsCommand(
    val visitId: VisitId,
    val studioId: StudioId,
    val stage: ProtocolStage
)

data class GenerateVisitProtocolsResult(
    val protocols: List<VisitProtocol>
)
