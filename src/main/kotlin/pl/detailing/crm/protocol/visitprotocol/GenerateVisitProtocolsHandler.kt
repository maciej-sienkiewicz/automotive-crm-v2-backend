package pl.detailing.crm.protocol.visitprotocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateRepository
import pl.detailing.crm.protocol.domain.ProtocolTemplateFormat
import pl.detailing.crm.protocol.domain.VisitProtocol
import pl.detailing.crm.protocol.infrastructure.*
import pl.detailing.crm.shared.*
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.visit.infrastructure.DocumentService
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

@Service
class GenerateVisitProtocolsHandler(
    private val protocolResolver: ProtocolResolver,
    private val visitProtocolRepository: VisitProtocolRepository,
    private val crmDataResolver: CrmDataResolver,
    private val pdfProcessingService: PdfProcessingService,
    private val htmlProtocolFillService: HtmlProtocolFillService,
    private val s3StorageService: S3ProtocolStorageService,
    private val protocolFieldMappingRepository: ProtocolFieldMappingRepository,
    private val protocolTemplateRepository: ProtocolTemplateRepository,
    private val consentTemplateRepository: ConsentTemplateRepository,
    private val consentDefinitionRepository: ConsentDefinitionRepository,
    private val studioSettingsRepository: StudioSettingsRepository,
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
                    serveConsentPdf(visitProtocol, command.studioId, visitNumber)
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
     * Zgoda nie niesie danych wizyty, ale niesie dane administratora — nazwę firmy,
     * adres, NIP, REGON, mail i stronę. Bez nich klient podpisywałby dokument z
     * pustymi miejscami, więc szablon jest wypełniany danymi studia i zapisywany
     * jako osobny plik wizyty. Pole podpisu zostaje interaktywne (fillPdfForm
     * spłaszcza wszystko poza nim), a gotowy plik trafia do dokumentów wizyty —
     * tak samo jak protokół przyjęcia.
     *
     * Gdy wypełnienie się nie uda, klient dostaje do podpisu sam szablon: lepszy
     * dokument bez danych administratora niż wizyta bez zgody do podpisania.
     */
    private suspend fun serveConsentPdf(
        visitProtocol: VisitProtocol,
        studioId: StudioId,
        visitNumber: String
    ): VisitProtocol {
        val consentTemplateId = visitProtocol.consentTemplateId ?: return visitProtocol

        val templateEntity = consentTemplateRepository.findByIdAndStudioId(
            consentTemplateId.value, studioId.value
        ) ?: run {
            logger.warn("Consent template ${consentTemplateId.value} not found — skipping")
            return visitProtocol
        }

        val filledS3Key = try {
            val settings = studioSettingsRepository.findById(studioId.value).orElse(null)
            val target = s3StorageService.buildFilledConsentPdfS3Key(
                studioId.value, visitProtocol.visitId.value, visitNumber, visitProtocol.id.value
            )
            pdfProcessingService.fillPdfForm(templateEntity.s3Key, companyFieldValues(settings), target)
            target
        } catch (e: Exception) {
            logger.error(
                "Failed to fill consent PDF for protocol ${visitProtocol.id}: ${e.message} — serving raw template",
                e
            )
            templateEntity.s3Key
        }

        val updated = visitProtocol.markAsReadyForSignature(filledS3Key)
        visitProtocolRepository.save(VisitProtocolEntity.fromDomain(updated))

        // Rejestrujemy tylko własny plik wizyty. Surowy szablon jest wspólny dla
        // całego studia — w dokumentach wizyty nie ma czego szukać.
        if (filledS3Key != templateEntity.s3Key) {
            registerConsentDocument(visitProtocol, studioId, visitNumber, filledS3Key)
        }
        return updated
    }

    /** Dane administratora wstawiane w miejsca oznaczone w szablonie zgody. */
    private fun companyFieldValues(settings: StudioSettingsEntity?): Map<String, String> {
        val streetLine = settings?.street.orEmpty().trim()
        val cityLine = listOfNotNull(
            settings?.postalCode?.trim()?.takeIf { it.isNotBlank() },
            settings?.city?.trim()?.takeIf { it.isNotBlank() }
        ).joinToString(" ")
        val fullAddress = listOf(streetLine, cityLine).filter { it.isNotBlank() }.joinToString(", ")
        val name = settings?.name.orEmpty().trim()

        return mapOf(
            "companyname" to name,
            "companycity" to settings?.city.orEmpty().trim(),
            "companyaddress" to fullAddress,
            "companynip" to settings?.taxId.orEmpty().trim(),
            "companyregon" to settings?.regon.orEmpty().trim(),
            "companyemail" to settings?.email.orEmpty().trim(),
            "companywebsite" to settings?.website.orEmpty().trim(),
            "companymailingaddress" to listOf(name, fullAddress)
                .filter { it.isNotBlank() }
                .joinToString(", ")
        )
    }

    private suspend fun registerConsentDocument(
        visitProtocol: VisitProtocol,
        studioId: StudioId,
        visitNumber: String,
        s3Key: String
    ) {
        try {
            val visitEntity = visitRepository.findById(visitProtocol.visitId.value).orElse(null) ?: return
            val definitionName = visitProtocol.consentDefinitionId?.let { defId ->
                consentDefinitionRepository.findByIdAndStudioId(defId.value, studioId.value)?.name
            } ?: "Zgoda"

            documentService.registerDocument(
                visitId = visitProtocol.visitId.value,
                customerId = visitEntity.customerId,
                documentType = DocumentType.PROTOCOL,
                name = "$definitionName — $visitNumber",
                s3Key = s3Key,
                fileName = "ZGD_${visitNumber}.pdf",
                createdBy = visitEntity.createdBy,
                createdByName = "System",
                category = "consent"
            )
        } catch (e: Exception) {
            logger.error("Failed to register consent as document: ${e.message}", e)
        }
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

            val template = protocolTemplateRepository.findByIdAndStudioId(templateId.value, studioId.value)
                ?.toDomain()
                ?: throw EntityNotFoundException("Szablon protokołu nie został znaleziony: $templateId")

            val filledS3Key = when (template.fileFormat) {
                ProtocolTemplateFormat.PDF -> {
                    val filledPdfS3Key = s3StorageService.buildFilledPdfS3Key(
                        studioId.value, visitProtocol.visitId.value, visitNumber, visitProtocol.version
                    )
                    pdfProcessingService.fillPdfForm(template.s3Key, fieldValues, filledPdfS3Key)
                    filledPdfS3Key
                }
                ProtocolTemplateFormat.HTML -> {
                    val filledHtmlS3Key = s3StorageService.buildFilledHtmlS3Key(
                        studioId.value, visitProtocol.visitId.value, visitNumber, visitProtocol.version
                    )
                    val templateHtml = String(s3StorageService.downloadBytes(template.s3Key), Charsets.UTF_8)
                    val checkboxFields = fieldMappings
                        .filter {
                            it.crmDataKey == CrmDataKey.VEHICLE_KEYS_RECEIVED ||
                                it.crmDataKey == CrmDataKey.VEHICLE_DOCUMENTS_RECEIVED
                        }
                        .map { it.pdfFieldName }
                        .toSet()
                    val filledHtml = htmlProtocolFillService.fill(templateHtml, fieldValues, checkboxFields)
                    s3StorageService.uploadBytes(
                        filledHtmlS3Key, filledHtml.toByteArray(Charsets.UTF_8), "text/html"
                    )
                    filledHtmlS3Key
                }
            }

            val updated = visitProtocol.markAsReadyForSignature(filledS3Key)
            visitProtocolRepository.save(VisitProtocolEntity.fromDomain(updated))

            val fileExtension = template.fileFormat.fileExtension
            try {
                val visitEntity = visitRepository.findById(visitProtocol.visitId.value).orElse(null)
                if (visitEntity != null) {
                    documentService.registerDocument(
                        visitId = visitProtocol.visitId.value,
                        customerId = visitEntity.customerId,
                        documentType = DocumentType.PROTOCOL,
                        name = "PPP_${visitNumber}_${visitProtocol.version}",
                        s3Key = filledS3Key,
                        fileName = "PPP_${visitNumber}_${visitProtocol.version}.$fileExtension",
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
