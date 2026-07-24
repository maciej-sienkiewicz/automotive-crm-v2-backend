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
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.infrastructure.SmsAutomationConfigEntity
import pl.detailing.crm.smscampaigns.infrastructure.SmsAutomationConfigJpaRepository
import pl.detailing.crm.visit.infrastructure.DocumentStorageService
import java.time.Instant
import java.util.UUID

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

// ── Controller ────────────────────────────────────────────────────────────────

@RequiresPermission(Permission.COMMUNICATION_SEND)
@RestController
@RequestMapping("/api/v1/sms-campaigns/sender-name")
class SmsSenderNameController(
    private val jpaRepository: SmsAutomationConfigJpaRepository,
    private val documentStorageService: DocumentStorageService
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
        val studioId = SecurityContextHelper.getCurrentStudioId().value
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
