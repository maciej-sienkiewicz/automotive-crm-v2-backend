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

        val studioName = withContext(Dispatchers.IO) {
            studioRepository.findByStudioId(studioId)?.name
        }

        val logoUrl = settings?.logoS3Key?.let { generateLogoPresignedUrl(it) }

        ResponseEntity.ok(
            CompanySettingsResponse(
                id = studioId.toString(),
                name = settings?.name ?: studioName ?: "",
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
                updatedAt = (settings?.updatedAt ?: Instant.now()).toString()
            )
        )
    }

    @PutMapping
    fun updateCompanySettings(
        @org.springframework.web.bind.annotation.RequestBody request: UpdateCompanySettingsRequest
    ): ResponseEntity<CompanySettingsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can update company settings")
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
                updatedAt = saved.updatedAt.toString()
            )
        )
    }

    @PostMapping("/logo", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadLogo(@RequestPart("file") file: MultipartFile): ResponseEntity<UploadLogoResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can upload company logo")
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
            throw ForbiddenException("Only OWNER and MANAGER can delete company logo")
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
