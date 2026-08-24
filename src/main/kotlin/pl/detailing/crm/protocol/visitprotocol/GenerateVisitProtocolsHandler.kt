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
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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

    private companion object {
        /** Dzień-miesiąc-rok: tak nazywa dokumenty człowiek szukający ich w folderze. */
        val DOCUMENT_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")
    }

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
                    fillProtocolPdf(visitProtocol, command.studioId, visitNumber, command.releasedByName)
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
            registerConsentDocument(visitProtocol, studioId, filledS3Key)
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
        s3Key: String
    ) {
        try {
            val visitEntity = visitRepository.findById(visitProtocol.visitId.value).orElse(null) ?: return
            val definitionName = visitProtocol.consentDefinitionId?.let { defId ->
                consentDefinitionRepository.findByIdAndStudioId(defId.value, studioId.value)?.name
            } ?: "Zgoda"

            // Ta sama konwencja co przy protokołach — z nazwą zgody zamiast etapu,
            // żeby RODO i zgody marketingowe nie stały w liście jako dwa takie same wiersze.
            val documentName = listOf(
                DOCUMENT_DATE_FORMAT.format(LocalDate.now()),
                visitEntity.brandSnapshot,
                visitEntity.modelSnapshot,
                definitionName
            ).map { slug(it) }.filter { it.isNotBlank() }.joinToString("_")

            documentService.registerDocument(
                visitId = visitProtocol.visitId.value,
                customerId = visitEntity.customerId,
                documentType = DocumentType.PROTOCOL,
                name = documentName,
                s3Key = s3Key,
                fileName = "$documentName.pdf",
                createdBy = visitEntity.createdBy,
                createdByName = "System",
                category = "consent"
            )
        } catch (e: Exception) {
            logger.error("Failed to register consent as document: ${e.message}", e)
        }
    }

    /**
     * Wartości, których nie da się wziąć z mapowań CRM, bo powstają dopiero przy
     * wydaniu: kto wydaje pojazd, numer protokołu przyjęcia tej samej wizyty oraz
     * odpowiedź o zgodności stanu wizualnego (wpisywana przez pracownika tuż przed
     * wysłaniem dokumentu do podpisu).
     *
     * Dla etapu przyjęcia mapa jest pusta — nie dokładamy pól, których tamten
     * szablon nie ma.
     */
    private fun checkOutValues(
        visitProtocol: VisitProtocol,
        studioId: StudioId,
        visitNumber: String,
        releasedByName: String?
    ): Map<String, String> {
        if (visitProtocol.stage != ProtocolStage.CHECK_OUT) return emptyMap()

        val entity = visitProtocolRepository.findByVisitIdAndIdAndStudioId(
            visitProtocol.visitId.value, visitProtocol.id.value, studioId.value
        )

        // Protokół przyjęcia tej wizyty rozpoznaje się po numerze wizyty — nazwy
        // plików niosą datę i pojazd, więc nie nadają się na odwołanie w dokumencie.
        val match = entity?.conditionMatch

        return buildMap {
            put("receptionprotocolnumber", visitNumber)
            put("releasedby", releasedByName ?: "")
            put("conditionmatch", if (match == true) "X" else "")
            put("conditionmismatch", if (match == false) "X" else "")
            put("conditionremarks", entity?.conditionRemarks.orEmpty())
        }
    }

    /**
     * Zapisuje odpowiedź o zgodności stanu wizualnego i przerysowuje protokół wydania.
     *
     * Wywoływane tuż przed wysłaniem dokumentu do podpisu: klient ma zobaczyć na
     * ekranie tablet/telefon dokładnie to, co pracownik zaznaczył. Plik nadpisuje
     * ten sam klucz w S3 — żądanie podpisu przypina skrót dokumentu dopiero przy
     * tworzeniu, więc podmiana przed nim jest bezpieczna, a po nim i tak zostałaby
     * odrzucona przez kontrolę integralności.
     */
    @Transactional
    suspend fun applyVisualCondition(command: ApplyVisualConditionCommand): VisitProtocol =
        withContext(Dispatchers.IO) {
            val entity = visitProtocolRepository.findByVisitIdAndIdAndStudioId(
                command.visitId.value, command.protocolId.value, command.studioId.value
            ) ?: throw EntityNotFoundException("Protokół nie został znaleziony")

            require(entity.stage == ProtocolStage.CHECK_OUT) {
                "Zgodność stanu wizualnego dotyczy wyłącznie protokołu wydania"
            }
            if (entity.status == VisitProtocolStatus.SIGNED) {
                throw ValidationException("Protokół jest już podpisany i nie może być zmieniony")
            }

            entity.conditionMatch = command.conditionMatch
            entity.conditionRemarks = command.remarks?.trim()?.takeIf { it.isNotBlank() }
            entity.updatedAt = Instant.now()
            visitProtocolRepository.save(entity)

            val visitEntity = visitRepository.findById(command.visitId.value).orElse(null)
                ?: throw EntityNotFoundException("Wizyta nie została znaleziona: ${command.visitId}")

            fillProtocolPdf(
                entity.toDomain(), command.studioId, visitEntity.visitNumber, command.releasedByName
            )
        }

    /**
     * Nazwa dokumentu widoczna w wizycie i na pobranym pliku.
     *
     * Wcześniej oba protokoły nazywały się `PPP_{numer}_{wersja}` — a że wersje
     * liczą się osobno dla każdego szablonu, przyjęcie i wydanie tej samej wizyty
     * dostawały ten sam napis i w liście dokumentów nie dało się ich rozróżnić.
     *
     * Teraz nazwa mówi wszystko, czego szuka się wzrokiem: kiedy, jaki samochód,
     * czyj i który to etap — `24-08-2026_Porsche_911_Wojcik_przyjecie`. Bez
     * polskich znaków i spacji, bo ta sama nazwa trafia do nazwy pliku.
     */
    private fun protocolDocumentName(
        visitProtocol: VisitProtocol,
        visitEntity: pl.detailing.crm.visit.infrastructure.VisitEntity,
        crmData: Map<CrmDataKey, String>,
        visitNumber: String
    ): String {
        val stageLabel = when (visitProtocol.stage) {
            ProtocolStage.CHECK_IN -> "przyjecie"
            ProtocolStage.CHECK_OUT -> "wydanie"
        }
        val surname = crmData[CrmDataKey.CUSTOMER_FULL_NAME]
            ?.trim()?.split(" ")?.lastOrNull().orEmpty()

        val parts = listOf(
            DOCUMENT_DATE_FORMAT.format(LocalDate.now()),
            visitEntity.brandSnapshot,
            visitEntity.modelSnapshot,
            surname,
            stageLabel
        ).map { slug(it) }.filter { it.isNotBlank() }

        // Kolejne wersje tego samego protokołu muszą się różnić, inaczej w liście
        // dokumentów stoją dwa identyczne wiersze.
        val suffix = if (visitProtocol.version > 1) "_v${visitProtocol.version}" else ""
        return parts.joinToString("_").ifBlank { "protokol_${slug(visitNumber)}_$stageLabel" } + suffix
    }

    /** ASCII bez spacji: nazwa dokumentu jest zarazem nazwą pliku do pobrania. */
    private fun slug(value: String): String =
        java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFD)
            .replace("\\p{M}".toRegex(), "")
            .replace("ł", "l").replace("Ł", "L")
            .replace("[^A-Za-z0-9-]+".toRegex(), "-")
            .trim('-')

    private suspend fun fillProtocolPdf(
        visitProtocol: VisitProtocol,
        studioId: StudioId,
        visitNumber: String,
        releasedByName: String? = null
    ): VisitProtocol {
        val templateId = visitProtocol.templateId ?: return visitProtocol

        return try {
            val crmData = crmDataResolver.resolveVisitData(visitProtocol.visitId, studioId)

            val fieldMappings = protocolFieldMappingRepository.findAllByTemplateIdAndStudioId(
                templateId.value, studioId.value
            )
            val fieldValues = fieldMappings.associate { mapping ->
                mapping.pdfFieldName to (crmData[mapping.crmDataKey] ?: "")
            } + checkOutValues(visitProtocol, studioId, visitNumber, releasedByName)

            val template = protocolTemplateRepository.findByIdAndStudioId(templateId.value, studioId.value)
                ?.toDomain()
                ?: throw EntityNotFoundException("Szablon protokołu nie został znaleziony: $templateId")

            val filledS3Key = when (template.fileFormat) {
                ProtocolTemplateFormat.PDF -> {
                    val filledPdfS3Key = s3StorageService.buildFilledPdfS3Key(
                        studioId.value, visitProtocol.visitId.value, visitNumber,
                        visitProtocol.version, visitProtocol.id.value
                    )
                    pdfProcessingService.fillPdfForm(template.s3Key, fieldValues, filledPdfS3Key)
                    filledPdfS3Key
                }
                ProtocolTemplateFormat.HTML -> {
                    val filledHtmlS3Key = s3StorageService.buildFilledHtmlS3Key(
                        studioId.value, visitProtocol.visitId.value, visitNumber,
                        visitProtocol.version, visitProtocol.id.value
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

            // Pierwsze wypełnienie przestawia protokół w stan „gotowy do podpisu".
            // Ponowne (po odpowiedzi o zgodności stanu wizualnego przy wydaniu)
            // podmienia tylko zawartość pliku pod tym samym kluczem — status,
            // klucz i wiersz dokumentu wizyty zostają, jakie były.
            val isFirstFill = visitProtocol.status == VisitProtocolStatus.PENDING
            val updated = if (isFirstFill) {
                visitProtocol.markAsReadyForSignature(filledS3Key).also {
                    visitProtocolRepository.save(VisitProtocolEntity.fromDomain(it))
                }
            } else {
                visitProtocol
            }

            val fileExtension = template.fileFormat.fileExtension
            if (isFirstFill) try {
                val visitEntity = visitRepository.findById(visitProtocol.visitId.value).orElse(null)
                if (visitEntity != null) {
                    val documentName = protocolDocumentName(visitProtocol, visitEntity, crmData, visitNumber)
                    documentService.registerDocument(
                        visitId = visitProtocol.visitId.value,
                        customerId = visitEntity.customerId,
                        documentType = DocumentType.PROTOCOL,
                        name = documentName,
                        s3Key = filledS3Key,
                        fileName = "$documentName.$fileExtension",
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
    val stage: ProtocolStage,
    /** Pracownik wydający pojazd — trafia na protokół wydania. */
    val releasedByName: String? = null
)

data class ApplyVisualConditionCommand(
    val visitId: VisitId,
    val protocolId: VisitProtocolId,
    val studioId: StudioId,
    val conditionMatch: Boolean,
    val remarks: String?,
    val releasedByName: String?
)

data class GenerateVisitProtocolsResult(
    val protocols: List<VisitProtocol>
)
