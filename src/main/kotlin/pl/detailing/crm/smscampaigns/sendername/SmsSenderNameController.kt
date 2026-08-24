package pl.detailing.crm.smscampaigns.sendername

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.infrastructure.SmsAutomationConfigEntity
import pl.detailing.crm.smscampaigns.infrastructure.SmsAutomationConfigJpaRepository
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.visit.infrastructure.DocumentStorageService
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.RequiresCapability

// ── DTOs ─────────────────────────────────────────────────────────────────────

data class SmsSenderNameDto(
    val senderName: String?,
    val confirmed: Boolean,
    val hasAuthDocument: Boolean,
    val authDocumentName: String?
)

data class UpdateSenderNameRequest(
    val senderName: String
)

/** Podpis narysowany na ekranie: PNG w base64, bez prefiksu `data:`. */
data class SignAuthorizationRequest(
    val signatureImageBase64: String
)

// ── Controller ────────────────────────────────────────────────────────────────

@RequiresPermission(Permission.COMMUNICATION_SEND)
@RequiresCapability(CapabilityKey.COMM_SEND_TRANSACTIONAL)
@RestController
@RequestMapping("/api/v1/sms-campaigns/sender-name")
class SmsSenderNameController(
    private val jpaRepository: SmsAutomationConfigJpaRepository,
    private val documentStorageService: DocumentStorageService,
    private val studioSettingsRepository: StudioSettingsRepository,
    private val authorizationDocumentService: SmsAuthorizationDocumentService,
    private val authorizationNotifier: SmsAuthorizationNotifier
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun getSenderName(): ResponseEntity<SmsSenderNameDto> {
        val studioId = SecurityContextHelper.getCurrentStudioId().value
        val entity = jpaRepository.findByStudioId(studioId)
        return ResponseEntity.ok(entity.toDto())
    }

    @PutMapping
    fun updateSenderName(@RequestBody request: UpdateSenderNameRequest): ResponseEntity<SmsSenderNameDto> {
        val studioId = SecurityContextHelper.getCurrentStudioId().value
        val name = request.senderName.trim()
        require(name.length in 1..11) { "Nazwa nadawcy musi mieć od 1 do 11 znaków" }

        val entity = jpaRepository.findByStudioId(studioId) ?: createDefaultEntity(studioId)
        entity.smsSenderName = name
        entity.smsApiNameConfirmed = false
        entity.updatedAt = Instant.now()
        jpaRepository.save(entity)

        logger.info("SMS sender name updated [studioId={}]", studioId)
        return ResponseEntity.ok(entity.toDto())
    }

    @PostMapping("/document", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadDocument(@RequestParam("file") file: MultipartFile): ResponseEntity<SmsSenderNameDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val studioId = principal.studioId.value
        val entity = jpaRepository.findByStudioId(studioId) ?: createDefaultEntity(studioId)

        val originalName = file.originalFilename ?: "upoważnienie.docx"
        val extension = originalName.substringAfterLast('.', "docx")
        val s3Key = "$studioId/sms-auth-docs/${Instant.now().toEpochMilli()}.$extension"

        val uploadedKey = documentStorageService.uploadDocument(
            s3Key = s3Key,
            fileBytes = file.bytes,
            contentType = file.contentType ?: "application/octet-stream"
        )

        entity.smsAuthDocumentS3Key = uploadedKey
        entity.smsAuthDocumentName = originalName
        entity.updatedAt = Instant.now()
        jpaRepository.save(entity)

        logger.info("SMS auth document uploaded [studioId={}, key={}]", studioId, uploadedKey)

        authorizationNotifier.notifyAuthorizationSubmitted(
            principal = principal,
            senderName = entity.smsSenderName,
            source = SmsAuthorizationNotifier.Source.UPLOADED,
            fileName = originalName,
            fileBytes = file.bytes,
            contentType = file.contentType ?: "application/octet-stream"
        )

        ResponseEntity.ok(entity.toDto())
    }

    /**
     * Podpisanie upoważnienia na ekranie — zamiast drukowania wzoru, podpisywania
     * ręcznie i wgrywania skanu. Dokument powstaje z danych, które system już ma:
     * dane właściciela nazwy z ustawień firmy, pole nadawcy z konfiguracji SMS,
     * data bieżąca. Gotowy plik ląduje w tym samym miejscu co wgrany ręcznie, więc
     * dalsza część procesu (weryfikacja u operatora) nie zmienia się ani trochę.
     */
    @PostMapping("/document/sign")
    fun signDocument(@RequestBody request: SignAuthorizationRequest): ResponseEntity<SmsSenderNameDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val studioId = principal.studioId.value
        val entity = jpaRepository.findByStudioId(studioId) ?: createDefaultEntity(studioId)

        // Bez nazwy nadawcy nie ma czego upoważniać — dokument byłby zgodą na puste pole.
        val senderName = entity.smsSenderName?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Najpierw zapisz nazwę nadawcy, potem podpisz upoważnienie")

        val settings = studioSettingsRepository.findById(studioId).orElse(null)
        val pdfBytes = authorizationDocumentService.buildSignedAuthorization(
            settings = settings,
            senderName = senderName,
            signaturePngBase64 = request.signatureImageBase64,
            today = LocalDate.now()
        )

        val s3Key = "$studioId/sms-auth-docs/${Instant.now().toEpochMilli()}.pdf"
        val uploadedKey = documentStorageService.uploadDocument(
            s3Key = s3Key,
            fileBytes = pdfBytes,
            contentType = "application/pdf"
        )

        entity.smsAuthDocumentS3Key = uploadedKey
        entity.smsAuthDocumentName = "upowaznienie-nadawcy-sms-podpisane.pdf"
        entity.updatedAt = Instant.now()
        jpaRepository.save(entity)

        logger.info("SMS auth document signed in app [studioId={}, key={}]", studioId, uploadedKey)

        // Nazwa nadawcy czeka teraz na ręczne zatwierdzenie po naszej stronie —
        // powiadomienie z dokumentem idzie tam, gdzie zgłoszenia problemów.
        authorizationNotifier.notifyAuthorizationSubmitted(
            principal = principal,
            senderName = senderName,
            source = SmsAuthorizationNotifier.Source.SIGNED_IN_APP,
            fileName = entity.smsAuthDocumentName ?: "upowaznienie-nadawcy-sms-podpisane.pdf",
            fileBytes = pdfBytes,
            contentType = "application/pdf"
        )

        ResponseEntity.ok(entity.toDto())
    }

    @GetMapping("/document-url")
    fun getDocumentUrl(): ResponseEntity<Map<String, String>> {
        val studioId = SecurityContextHelper.getCurrentStudioId().value
        val entity = jpaRepository.findByStudioId(studioId)
        val s3Key = entity?.smsAuthDocumentS3Key
            ?: throw EntityNotFoundException("Nie przesłano jeszcze upoważnienia")

        val url = documentStorageService.generateDownloadUrl(s3Key)
        return ResponseEntity.ok(mapOf("url" to url))
    }

    @GetMapping("/template")
    fun downloadTemplate(): ResponseEntity<ByteArray> {
        val resource = ClassPathResource("sms-auth-template.docx")
        val bytes = resource.inputStream.use { it.readBytes() }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"upoważnienie-nadawcy-sms.docx\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .body(bytes)
    }

    private fun createDefaultEntity(studioId: UUID): SmsAutomationConfigEntity {
        val defaults = SmsAutomationConfig.defaultFor(StudioId(studioId))
        val entity = SmsAutomationConfigEntity.fromDomain(defaults)
        return jpaRepository.save(entity)
    }

    private fun SmsAutomationConfigEntity?.toDto() = SmsSenderNameDto(
        senderName = this?.smsSenderName,
        confirmed = this?.smsApiNameConfirmed ?: false,
        hasAuthDocument = this?.smsAuthDocumentS3Key != null,
        authDocumentName = this?.smsAuthDocumentName
    )
}
