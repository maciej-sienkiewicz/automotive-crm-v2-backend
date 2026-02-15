package pl.detailing.crm.protocol.visitprotocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.protocol.domain.VisitProtocol
import pl.detailing.crm.protocol.infrastructure.*
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.DocumentService
import pl.detailing.crm.visit.infrastructure.VisitRepository
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
    private val protocolFieldMappingRepository: ProtocolFieldMappingRepository,
    private val protocolTemplateRepository: ProtocolTemplateRepository,
    private val visitRepository: VisitRepository,
    private val documentService: DocumentService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(command: GenerateVisitProtocolsCommand): GenerateVisitProtocolsResult =
        withContext(Dispatchers.IO) {
            val totalStart = System.currentTimeMillis()
            logger.info("[PERF] Starting protocol generation for visit ${command.visitId}")

            // Check if protocols already exist for this visit and stage
            val checkStart = System.currentTimeMillis()
            val existingProtocols = visitProtocolRepository.findAllByVisitIdAndStudioIdAndStage(
                command.visitId.value,
                command.studioId.value,
                command.stage
            )
            logger.info("[PERF] Check existing protocols: ${System.currentTimeMillis() - checkStart}ms")

            if (existingProtocols.isNotEmpty()) {
                // Protocols already exist, return them
                return@withContext GenerateVisitProtocolsResult(
                    protocols = existingProtocols.map { it.toDomain() }
                )
            }

            // Get visit to retrieve visit number
            val visitEntity = visitRepository.findById(command.visitId.value).orElse(null)
                ?: throw EntityNotFoundException("Visit not found: ${command.visitId}")
            val visitNumber = visitEntity.visitNumber

            // Resolve required protocols for this visit
            val resolveStart = System.currentTimeMillis()
            val requiredRules = protocolResolver.resolveRequiredProtocols(
                command.visitId,
                command.studioId,
                command.stage
            )
            logger.info("[PERF] Resolve rules: ${System.currentTimeMillis() - resolveStart}ms (${requiredRules.size} rules)")

            // Create visit protocol instances
            val visitProtocols = requiredRules.map { rule ->
                val createStart = System.currentTimeMillis()

                // Get next version number for this template
                val maxVersion = visitProtocolRepository.findMaxVersionByVisitAndStageAndTemplate(
                    visitId = command.visitId.value,
                    studioId = command.studioId.value,
                    stage = command.stage,
                    templateId = rule.templateId.value
                )
                val nextVersion = maxVersion + 1

                val visitProtocol = VisitProtocol(
                    id = VisitProtocolId.random(),
                    studioId = command.studioId,
                    visitId = command.visitId,
                    templateId = rule.templateId,
                    stage = command.stage,
                    version = nextVersion,
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

                val saveStart = System.currentTimeMillis()
                val entity = VisitProtocolEntity.fromDomain(visitProtocol)
                visitProtocolRepository.save(entity)
                logger.info("[PERF] Save protocol entity: ${System.currentTimeMillis() - saveStart}ms")

                // Automatically fill the PDF with CRM data
                val fillStart = System.currentTimeMillis()
                val updatedProtocol = fillProtocolPdf(visitProtocol, command.studioId, visitNumber)
                logger.info("[PERF] Fill PDF total: ${System.currentTimeMillis() - fillStart}ms")

                logger.info("[PERF] Single protocol creation: ${System.currentTimeMillis() - createStart}ms")
                updatedProtocol
            }

            logger.info("[PERF] TOTAL protocol generation: ${System.currentTimeMillis() - totalStart}ms")
            GenerateVisitProtocolsResult(visitProtocols)
        }

    private suspend fun fillProtocolPdf(visitProtocol: VisitProtocol, studioId: StudioId, visitNumber: String): VisitProtocol {
        return try {
            // Resolve CRM data for the visit
            val crmStart = System.currentTimeMillis()
            val crmData = crmDataResolver.resolveVisitData(visitProtocol.visitId, studioId)
            logger.info("[PERF]   - CRM data resolution: ${System.currentTimeMillis() - crmStart}ms")

            // Get field mappings for this template
            val mappingStart = System.currentTimeMillis()
            val fieldMappings = protocolFieldMappingRepository.findAllByTemplateIdAndStudioId(
                visitProtocol.templateId.value,
                studioId.value
            )
            logger.info("[PERF]   - Field mappings query: ${System.currentTimeMillis() - mappingStart}ms (${fieldMappings.size} mappings)")

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

            // Build output S3 key for filled PDF with visit number and version
            val filledPdfS3Key = s3StorageService.buildFilledPdfS3Key(
                studioId.value,
                visitProtocol.visitId.value,
                visitNumber,
                visitProtocol.version
            )

            // Fill the PDF
            val pdfStart = System.currentTimeMillis()
            pdfProcessingService.fillPdfForm(templateS3Key, fieldValues, filledPdfS3Key)
            logger.info("[PERF]   - PDF processing (download + fill + upload): ${System.currentTimeMillis() - pdfStart}ms")

            // Update protocol status
            val updateStart = System.currentTimeMillis()
            val updated = visitProtocol.markAsReadyForSignature(filledPdfS3Key)
            val entity = VisitProtocolEntity.fromDomain(updated)
            visitProtocolRepository.save(entity)
            logger.info("[PERF]   - Update status: ${System.currentTimeMillis() - updateStart}ms")

            // Register protocol as a document in the visit documents system
            val docRegisterStart = System.currentTimeMillis()
            try {
                // Get visit entity to retrieve customerId and userId
                val visitEntity = visitRepository.findById(visitProtocol.visitId.value).orElse(null)

                if (visitEntity != null) {
                    // Get template name
                    val template = protocolTemplateRepository.findByIdAndStudioId(
                        visitProtocol.templateId.value,
                        studioId.value
                    )
                    val templateName = template?.name ?: "Protocol"

                    // Register as document with new naming convention
                    documentService.registerDocument(
                        visitId = visitProtocol.visitId.value,
                        customerId = visitEntity.customerId,
                        documentType = DocumentType.PROTOCOL,
                        name = "$templateName - ${if (visitProtocol.stage == ProtocolStage.CHECK_IN) "Przyjęcie" else "Wydanie"}",
                        s3Key = filledPdfS3Key,
                        fileName = "PPP_${visitNumber}_${visitProtocol.version}.pdf",
                        createdBy = visitEntity.createdBy,
                        createdByName = "System", // Will be updated when signed
                        category = "protocol"
                    )
                    logger.info("[PERF]   - Document registration: ${System.currentTimeMillis() - docRegisterStart}ms")
                } else {
                    logger.warn("Could not register protocol as document - visit not found: ${visitProtocol.visitId}")
                }
            } catch (e: Exception) {
                // Log but don't fail the protocol generation
                logger.error("Failed to register protocol as document: ${e.message}", e)
            }

            updated
        } catch (e: Exception) {
            // Log error but don't fail the entire generation
            logger.error("Failed to fill PDF for protocol ${visitProtocol.id}: ${e.message}", e)
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
