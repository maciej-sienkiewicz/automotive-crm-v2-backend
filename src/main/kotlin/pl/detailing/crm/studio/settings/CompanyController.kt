package pl.detailing.crm.studio.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.permission.RequiresOwner
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.infrastructure.SmsAutomationConfigEntity
import pl.detailing.crm.smscampaigns.infrastructure.SmsAutomationConfigJpaRepository
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.numbering.NumberingTemplate
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.visit.convert.VisitNumberGenerator
import java.time.LocalDate
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import java.time.Duration
import java.time.Instant

@RestController
@RequestMapping("/api/v1/company")
class CompanyController(
    private val studioSettingsRepository: StudioSettingsRepository,
    private val studioRepository: StudioRepository,
    private val smsAutomationConfigRepository: SmsAutomationConfigJpaRepository,
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String
) {
    companion object {
        private val logger = LoggerFactory.getLogger(CompanyController::class.java)
        private val LOGO_URL_TTL = Duration.ofHours(24)
        private val ALLOWED_LOGO_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/svg+xml")
        private const val MAX_LOGO_SIZE_BYTES = 5 * 1024 * 1024L
    }

    @GetMapping
    fun getCompanySettings(): ResponseEntity<CompanySettingsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val studioId = principal.studioId.value

        val settings = withContext(Dispatchers.IO) {
            studioSettingsRepository.findById(studioId).orElse(null)
        }

        val studioEntity = withContext(Dispatchers.IO) {
            studioRepository.findByStudioId(studioId)
        }

        val logoUrl = settings?.logoS3Key?.let { generateLogoPresignedUrl(it) }

        val senderNameConfirmed = withContext(Dispatchers.IO) {
            smsAutomationConfigRepository.findByStudioId(studioId)?.smsApiNameConfirmed ?: false
        }

        ResponseEntity.ok(
            CompanySettingsResponse(
                id = studioId.toString(),
                name = settings?.name ?: studioEntity?.name ?: "",
                taxId = settings?.taxId,
                regon = settings?.regon,
                street = settings?.street,
                postalCode = settings?.postalCode,
                city = settings?.city,
                phone = settings?.phone,
                email = settings?.email,
                website = settings?.website,
                bankAccount = settings?.bankAccount,
                logoUrl = logoUrl,
                emailAlias = studioEntity?.emailAlias,
                smsApiNameConfirmed = senderNameConfirmed,
                updatedAt = (settings?.updatedAt ?: Instant.now()).toString()
            )
        )
    }

    @GetMapping("/email-alias")
    fun getEmailAlias(): ResponseEntity<EmailAliasResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val alias = withContext(Dispatchers.IO) {
            studioRepository.findByStudioId(principal.studioId.value)?.emailAlias
        }
        ResponseEntity.ok(EmailAliasResponse(emailAlias = alias))
    }

    @PutMapping
    @RequiresOwner
    fun updateCompanySettings(
        @org.springframework.web.bind.annotation.RequestBody request: UpdateCompanySettingsRequest
    ): ResponseEntity<CompanySettingsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()


        val studioId = principal.studioId.value

        val settings = withContext(Dispatchers.IO) {
            studioSettingsRepository.findById(studioId).orElse(null)
                ?: StudioSettingsEntity(studioId = studioId)
        }

        request.name?.let { settings.name = it }
        request.taxId?.let { settings.taxId = it }
        request.regon?.let { settings.regon = it }
        request.street?.let { settings.street = it }
        request.postalCode?.let { settings.postalCode = it }
        request.city?.let { settings.city = it }
        request.phone?.let { settings.phone = it }
        request.email?.let { settings.email = it }
        request.website?.let { settings.website = it }
        request.bankAccount?.let { settings.bankAccount = it }
        settings.updatedAt = Instant.now()

        val saved = withContext(Dispatchers.IO) { studioSettingsRepository.save(settings) }
        val logoUrl = saved.logoS3Key?.let { generateLogoPresignedUrl(it) }

        val studioEmailAlias = withContext(Dispatchers.IO) {
            studioRepository.findByStudioId(studioId)?.emailAlias
        }

        val senderNameConfirmed = withContext(Dispatchers.IO) {
            smsAutomationConfigRepository.findByStudioId(studioId)?.smsApiNameConfirmed ?: false
        }

        ResponseEntity.ok(
            CompanySettingsResponse(
                id = studioId.toString(),
                name = saved.name ?: "",
                taxId = saved.taxId,
                regon = saved.regon,
                street = saved.street,
                postalCode = saved.postalCode,
                city = saved.city,
                phone = saved.phone,
                email = saved.email,
                website = saved.website,
                bankAccount = saved.bankAccount,
                logoUrl = logoUrl,
                emailAlias = studioEmailAlias,
                smsApiNameConfirmed = senderNameConfirmed,
                updatedAt = saved.updatedAt.toString()
            )
        )
    }

    @PostMapping("/logo", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @RequiresOwner
    fun uploadLogo(@RequestPart("file") file: MultipartFile): ResponseEntity<UploadLogoResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()


        val contentType = file.contentType ?: "application/octet-stream"
        if (contentType !in ALLOWED_LOGO_CONTENT_TYPES) {
            throw IllegalArgumentException("Unsupported logo format. Allowed: JPEG, PNG, WebP, SVG")
        }
        if (file.size > MAX_LOGO_SIZE_BYTES) {
            throw IllegalArgumentException("Logo file exceeds the 5 MB size limit")
        }

        val studioId = principal.studioId.value
        val extension = contentType.substringAfter("/").replace("svg+xml", "svg")
        val s3Key = "$studioId/logo/logo.$extension"

        withContext(Dispatchers.IO) {
            val putRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .contentType(contentType)
                .contentLength(file.size)
                .build()
            s3Client.putObject(putRequest, RequestBody.fromBytes(file.bytes))
            logger.info("Uploaded logo for studio $studioId to S3: $s3Key")
        }

        val settings = withContext(Dispatchers.IO) {
            val entity = studioSettingsRepository.findById(studioId).orElse(null)
                ?: StudioSettingsEntity(studioId = studioId)
            entity.logoS3Key = s3Key
            entity.updatedAt = Instant.now()
            studioSettingsRepository.save(entity)
        }

        val logoUrl = generateLogoPresignedUrl(settings.logoS3Key!!)
        ResponseEntity.ok(UploadLogoResponse(logoUrl = logoUrl))
    }

    @DeleteMapping("/logo")
    @RequiresOwner
    fun deleteLogo(): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()


        val studioId = principal.studioId.value

        val settings = withContext(Dispatchers.IO) {
            studioSettingsRepository.findById(studioId).orElse(null)
        }

        if (settings?.logoS3Key != null) {
            withContext(Dispatchers.IO) {
                val deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(settings.logoS3Key!!)
                    .build()
                s3Client.deleteObject(deleteRequest)
                logger.info("Deleted logo for studio $studioId from S3: ${settings.logoS3Key}")

                settings.logoS3Key = null
                settings.updatedAt = Instant.now()
                studioSettingsRepository.save(settings)
            }
        }

        ResponseEntity.noContent().build()
    }

    @GetMapping("/lead-alert-config")
    fun getLeadAlertConfig(): ResponseEntity<LeadAlertConfigResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val settings = withContext(Dispatchers.IO) {
            studioSettingsRepository.findById(principal.studioId.value).orElse(null)
        }
        ResponseEntity.ok(
            LeadAlertConfigResponse(
                leadStagnantOurThresholdHours = settings?.leadStagnantOurThresholdHours ?: 48,
                leadStagnantClientThresholdHours = settings?.leadStagnantClientThresholdHours ?: 72
            )
        )
    }

    @PatchMapping("/lead-alert-config")
    @RequiresOwner
    fun updateLeadAlertConfig(
        @org.springframework.web.bind.annotation.RequestBody request: UpdateLeadAlertConfigRequest
    ): ResponseEntity<LeadAlertConfigResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()


        val studioId = principal.studioId.value
        val settings = withContext(Dispatchers.IO) {
            studioSettingsRepository.findById(studioId).orElse(null)
                ?: StudioSettingsEntity(studioId = studioId)
        }

        request.leadStagnantOurThresholdHours?.let {
            require(it in 1..720) { "Próg musi być między 1 a 720 godzin" }
            settings.leadStagnantOurThresholdHours = it
        }
        request.leadStagnantClientThresholdHours?.let {
            require(it in 1..720) { "Próg musi być między 1 a 720 godzin" }
            settings.leadStagnantClientThresholdHours = it
        }
        settings.updatedAt = Instant.now()

        val saved = withContext(Dispatchers.IO) { studioSettingsRepository.save(settings) }

        ResponseEntity.ok(
            LeadAlertConfigResponse(
                leadStagnantOurThresholdHours = saved.leadStagnantOurThresholdHours,
                leadStagnantClientThresholdHours = saved.leadStagnantClientThresholdHours
            )
        )
    }

    @PatchMapping("/sms-sender-config")
    @RequiresOwner
    fun updateSmsSenderConfig(
        @org.springframework.web.bind.annotation.RequestBody request: UpdateSmsSenderConfigRequest
    ): ResponseEntity<SmsSenderConfigResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val studioId = principal.studioId.value

        // The confirmation flag lives next to the sender name it confirms — in
        // sms_automation_configs — because that pair is what every outbound SMS reads.
        val config = withContext(Dispatchers.IO) {
            smsAutomationConfigRepository.findByStudioId(studioId)
                ?: SmsAutomationConfigEntity.fromDomain(SmsAutomationConfig.defaultFor(StudioId(studioId)))
        }

        config.smsApiNameConfirmed = request.smsApiNameConfirmed
        config.updatedAt = Instant.now()

        val saved = withContext(Dispatchers.IO) { smsAutomationConfigRepository.save(config) }
        val effectiveSenderName = saved.smsSenderName?.trim()
            ?.takeIf { saved.smsApiNameConfirmed && it.isNotEmpty() }

        logger.info(
            "SMS sender name config updated for studio={} smsApiNameConfirmed={} senderName={}",
            studioId, saved.smsApiNameConfirmed, effectiveSenderName ?: "(ECO)"
        )

        ResponseEntity.ok(
            SmsSenderConfigResponse(
                smsApiNameConfirmed = saved.smsApiNameConfirmed,
                effectiveSenderName = effectiveSenderName
            )
        )
    }

    @GetMapping("/idle-timeout")
    fun getIdleTimeout(): ResponseEntity<IdleTimeoutResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val settings = withContext(Dispatchers.IO) {
            studioSettingsRepository.findById(principal.studioId.value).orElse(null)
        }
        ResponseEntity.ok(IdleTimeoutResponse(idleTimeoutSeconds = settings?.idleTimeoutSeconds ?: 0))
    }

    @PatchMapping("/idle-timeout")
    @RequiresOwner
    fun updateIdleTimeout(
        @org.springframework.web.bind.annotation.RequestBody request: UpdateIdleTimeoutRequest
    ): ResponseEntity<IdleTimeoutResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (!principal.isOwner) throw ForbiddenException("Only owners can change idle timeout settings")

        require(request.idleTimeoutSeconds in 0..86400) { "Idle timeout must be between 0 and 86400 seconds" }

        val studioId = principal.studioId.value
        val settings = withContext(Dispatchers.IO) {
            studioSettingsRepository.findById(studioId).orElse(null)
                ?: StudioSettingsEntity(studioId = studioId)
        }

        settings.idleTimeoutSeconds = request.idleTimeoutSeconds
        settings.updatedAt = Instant.now()

        val saved = withContext(Dispatchers.IO) { studioSettingsRepository.save(settings) }
        ResponseEntity.ok(IdleTimeoutResponse(idleTimeoutSeconds = saved.idleTimeoutSeconds))
    }

    @GetMapping("/visit-numbering-config")
    fun getVisitNumberingConfig(): ResponseEntity<VisitNumberingConfigResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val settings = withContext(Dispatchers.IO) {
            studioSettingsRepository.findById(principal.studioId.value).orElse(null)
        }
        val format = settings?.visitNumberFormat?.takeIf { it.isNotBlank() } ?: VisitNumberGenerator.DEFAULT_FORMAT
        val sequenceLength = settings?.visitNumberSequenceLength ?: VisitNumberGenerator.DEFAULT_SEQUENCE_LENGTH
        val randomLength = settings?.visitNumberRandomLength ?: VisitNumberGenerator.DEFAULT_RANDOM_LENGTH
        val template = NumberingTemplate(format, sequenceLength, randomLength)

        ResponseEntity.ok(
            VisitNumberingConfigResponse(
                format = format,
                sequenceLength = sequenceLength,
                randomLength = randomLength,
                preview = previewOf(template)
            )
        )
    }

    @PatchMapping("/visit-numbering-config")
    @RequiresOwner
    fun updateVisitNumberingConfig(
        @org.springframework.web.bind.annotation.RequestBody request: UpdateVisitNumberingConfigRequest
    ): ResponseEntity<VisitNumberingConfigResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val studioId = principal.studioId.value

        val format = request.format.trim()
        val errors = NumberingTemplate.validate(format).toMutableList()
        if (request.sequenceLength !in 1..10) {
            errors += "Długość numeru porządkowego musi być między 1 a 10 cyfr"
        }
        if (request.randomLength !in 1..12) {
            errors += "Długość numeru losowego musi być między 1 a 12 cyfr"
        }
        if (errors.isNotEmpty()) throw ValidationException(errors.joinToString("; "))

        // Constructing it is itself the final validation pass (catches anything the
        // static check above didn't, e.g. future rule additions) and gives us the preview.
        val template = NumberingTemplate(format, request.sequenceLength, request.randomLength)

        val settings = withContext(Dispatchers.IO) {
            studioSettingsRepository.findById(studioId).orElse(null)
                ?: StudioSettingsEntity(studioId = studioId)
        }
        settings.visitNumberFormat = format
        settings.visitNumberSequenceLength = request.sequenceLength
        settings.visitNumberRandomLength = request.randomLength
        settings.updatedAt = Instant.now()

        val saved = withContext(Dispatchers.IO) { studioSettingsRepository.save(settings) }

        ResponseEntity.ok(
            VisitNumberingConfigResponse(
                format = saved.visitNumberFormat!!,
                sequenceLength = saved.visitNumberSequenceLength,
                randomLength = saved.visitNumberRandomLength,
                preview = previewOf(template)
            )
        )
    }

    /** {SEQ} previews as sequence 1; {RAND} previews with a freshly drawn example — not idempotent by design. */
    private fun previewOf(template: NumberingTemplate): String = when (template.kind) {
        NumberingTemplate.Kind.RANDOM -> template.renderRandom(LocalDate.now())
        NumberingTemplate.Kind.SEQUENTIAL -> template.render(LocalDate.now(), 1)
    }

    private fun generateLogoPresignedUrl(s3Key: String): String {
        val getObjectRequest = GetObjectRequest.builder()
            .bucket(bucketName)
            .key(s3Key)
            .build()

        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(LOGO_URL_TTL)
            .getObjectRequest(getObjectRequest)
            .build()

        return s3Presigner.presignGetObject(presignRequest).url().toString()
    }
}

data class CompanySettingsResponse(
    val id: String,
    val name: String,
    val taxId: String?,
    val regon: String?,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val phone: String?,
    val email: String?,
    val website: String?,
    val bankAccount: String?,
    val logoUrl: String?,
    val emailAlias: String?,
    val smsApiNameConfirmed: Boolean,
    val updatedAt: String
)

data class UpdateCompanySettingsRequest(
    val name: String?,
    val taxId: String?,
    val regon: String?,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val phone: String?,
    val email: String?,
    val website: String?,
    val bankAccount: String?
)

data class UploadLogoResponse(val logoUrl: String)

data class EmailAliasResponse(val emailAlias: String?)

data class LeadAlertConfigResponse(
    val leadStagnantOurThresholdHours: Int,
    val leadStagnantClientThresholdHours: Int
)

data class UpdateLeadAlertConfigRequest(
    val leadStagnantOurThresholdHours: Int?,
    val leadStagnantClientThresholdHours: Int?
)

data class UpdateSmsSenderConfigRequest(
    val smsApiNameConfirmed: Boolean
)

data class SmsSenderConfigResponse(
    val smsApiNameConfirmed: Boolean,
    val effectiveSenderName: String?
)

data class IdleTimeoutResponse(val idleTimeoutSeconds: Int)

data class UpdateIdleTimeoutRequest(val idleTimeoutSeconds: Int)

data class VisitNumberingConfigResponse(
    val format: String,
    val sequenceLength: Int,
    val randomLength: Int,
    /** Example number for today's date — sequence 1 for {SEQ} formats, a fresh draw for {RAND} ones. */
    val preview: String
)

data class UpdateVisitNumberingConfigRequest(
    val format: String,
    val sequenceLength: Int,
    val randomLength: Int
)
