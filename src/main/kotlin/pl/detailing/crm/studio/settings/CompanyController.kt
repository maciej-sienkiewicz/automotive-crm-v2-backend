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
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UserRole
import pl.detailing.crm.studio.infrastructure.StudioRepository
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
    fun updateCompanySettings(
        @org.springframework.web.bind.annotation.RequestBody request: UpdateCompanySettingsRequest
    ): ResponseEntity<CompanySettingsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Tylko właściciel i menedżer mogą aktualizować ustawienia firmy")
        }

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
                updatedAt = saved.updatedAt.toString()
            )
        )
    }

    @PostMapping("/logo", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadLogo(@RequestPart("file") file: MultipartFile): ResponseEntity<UploadLogoResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Tylko właściciel i menedżer mogą przesyłać logo firmy")
        }

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
    fun deleteLogo(): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Tylko właściciel i menedżer mogą usuwać logo firmy")
        }

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
    fun updateLeadAlertConfig(
        @org.springframework.web.bind.annotation.RequestBody request: UpdateLeadAlertConfigRequest
    ): ResponseEntity<LeadAlertConfigResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Tylko właściciel i menedżer mogą zmieniać konfigurację alertów")
        }

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
