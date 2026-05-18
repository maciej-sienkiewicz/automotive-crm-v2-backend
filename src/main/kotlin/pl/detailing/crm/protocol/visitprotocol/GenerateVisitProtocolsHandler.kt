package pl.detailing.crm.protocol.visitprotocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateRepository
import pl.detailing.crm.protocol.domain.VisitProtocol
import pl.detailing.crm.protocol.infrastructure.*
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.DocumentService
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

@Service
class GenerateVisitProtocolsHandler(
    private val protocolResolver: ProtocolResolver,
    private val visitProtocolRepository: VisitProtocolRepository,
    private val crmDataResolver: CrmDataResolver,
    private val pdfProcessingService: PdfProcessingService,
    private val s3StorageService: S3ProtocolStorageService,
    private val protocolFieldMappingRepository: ProtocolFieldMappingRepository,
    private val protocolTemplateRepository: ProtocolTemplateRepository,
    private val consentTemplateRepository: ConsentTemplateRepository,
    private val visitRepository: VisitRepository,
    private val documentService: DocumentService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(command: GenerateVisitProtocolsCommand): GenerateVisitProtocolsResult =
        withContext(Dispatchers.IO) {
            val totalStart = System.currentTimeMillis()
            logger.info("[PERF] Starting protocol generation for visit ${command.visitId}")

            val existingProtocols = visitProtocolRepository.findAllByVisitIdAndStudioIdAndStage(
                command.visitId.value, command.studioId.value, command.stage
            )

            if (existingProtocols.isNotEmpty()) {
                return@withContext GenerateVisitProtocolsResult(
                    protocols = existingProtocols.map { it.toDomain() }
                )
            }

            val visitEntity = visitRepository.findById(command.visitId.value).orElse(null)
                ?: throw EntityNotFoundException("Wizyta nie została znaleziona: ${command.visitId}")
            val visitNumber = visitEntity.visitNumber

            val resolveStart = System.currentTimeMillis()
            val resolvedItems = protocolResolver.resolveRequiredProtocols(
                command.visitId, command.studioId, command.stage
            )
            logger.info("[PERF] Resolve: ${System.currentTimeMillis() - resolveStart}ms (${resolvedItems.size} items)")

            val visitProtocols = resolvedItems.map { resolved ->
                val createStart = System.currentTimeMillis()

                val maxVersion = visitProtocolRepository.findMaxVersionByVisitAndStageAndTemplate(
                    visitId = command.visitId.value,
                    studioId = command.studioId.value,
                    stage = command.stage,
                    templateId = resolved.templateId?.value ?: resolved.consentTemplateId!!.value
                )

                val visitProtocol = VisitProtocol(
                    id = VisitProtocolId.random(),
                    studioId = command.studioId,
                    visitId = command.visitId,
                    templateId = resolved.templateId,
                    consentTemplateId = resolved.consentTemplateId,
                    stage = command.stage,
                    version = maxVersion + 1,
                    status = VisitProtocolStatus.PENDING,
                    consentDefinitionId = resolved.consentDefinitionId,
                    filledPdfS3Key = null,
                    signedPdfS3Key = null,
                    signedAt = null,
                    signedBy = null,
                    signatureImageS3Key = null,
                    notes = null,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now()
                )

                visitProtocolRepository.save(VisitProtocolEntity.fromDomain(visitProtocol))

                val updatedProtocol = if (resolved.isConsentProtocol) {
                    serveConsentPdf(visitProtocol, command.studioId)
                } else {
                    fillProtocolPdf(visitProtocol, command.studioId, visitNumber)
                }

                logger.info("[PERF] Single protocol: ${System.currentTimeMillis() - createStart}ms")
                updatedProtocol
            }

            logger.info("[PERF] TOTAL: ${System.currentTimeMillis() - totalStart}ms")
            GenerateVisitProtocolsResult(visitProtocols)
        }

    /**
     * Consent protocols don't need PDF auto-fill — the consent template PDF is served directly.
     * Status jumps straight to READY_FOR_SIGNATURE.
     */
    private fun serveConsentPdf(visitProtocol: VisitProtocol, studioId: StudioId): VisitProtocol {
        val consentTemplateId = visitProtocol.consentTemplateId ?: return visitProtocol

        val templateEntity = consentTemplateRepository.findByIdAndStudioId(
            consentTemplateId.value, studioId.value
        ) ?: run {
            logger.warn("Consent template ${consentTemplateId.value} not found — skipping")
            return visitProtocol
        }

        val updated = visitProtocol.markAsReadyForSignature(templateEntity.s3Key)
        visitProtocolRepository.save(VisitProtocolEntity.fromDomain(updated))
        return updated
    }

    private suspend fun fillProtocolPdf(
        visitProtocol: VisitProtocol,
        studioId: StudioId,
        visitNumber: String
    ): VisitProtocol {
        val templateId = visitProtocol.templateId ?: return visitProtocol

        return try {
            val crmData = crmDataResolver.resolveVisitData(visitProtocol.visitId, studioId)

            val fieldMappings = protocolFieldMappingRepository.findAllByTemplateIdAndStudioId(
                templateId.value, studioId.value
            )
            val fieldValues = fieldMappings.associate { mapping ->
                mapping.pdfFieldName to (crmData[mapping.crmDataKey] ?: "")
            }

            val templateS3Key = s3StorageService.buildTemplateS3Key(studioId.value, templateId.value)
            val filledPdfS3Key = s3StorageService.buildFilledPdfS3Key(
                studioId.value, visitProtocol.visitId.value, visitNumber, visitProtocol.version
            )

            pdfProcessingService.fillPdfForm(templateS3Key, fieldValues, filledPdfS3Key)

            val updated = visitProtocol.markAsReadyForSignature(filledPdfS3Key)
            visitProtocolRepository.save(VisitProtocolEntity.fromDomain(updated))

            try {
                val visitEntity = visitRepository.findById(visitProtocol.visitId.value).orElse(null)
                if (visitEntity != null) {
                    documentService.registerDocument(
                        visitId = visitProtocol.visitId.value,
                        customerId = visitEntity.customerId,
                        documentType = DocumentType.PROTOCOL,
                        name = "PPP_${visitNumber}_${visitProtocol.version}",
                        s3Key = filledPdfS3Key,
                        fileName = "PPP_${visitNumber}_${visitProtocol.version}.pdf",
                        createdBy = visitEntity.createdBy,
                        createdByName = "System",
                        category = "protocol"
                    )
                } else {
                    logger.warn("Could not register protocol as document — visit not found: ${visitProtocol.visitId}")
                }
            } catch (e: Exception) {
                logger.error("Failed to register protocol as document: ${e.message}", e)
            }

            updated
        } catch (e: Exception) {
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
