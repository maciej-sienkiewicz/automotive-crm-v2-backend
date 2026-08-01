package pl.detailing.crm.employee.signature

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.signing.infrastructure.SignatureImageProcessor
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Service
class EmployeeSignatureService(
    private val employeeRepository: EmployeeRepository,
    private val signatureImageProcessor: SignatureImageProcessor,
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private val PRESIGN_DURATION = Duration.ofMinutes(10)
    }

    @Transactional
    fun saveSignature(
        studioId: StudioId,
        employeeId: EmployeeId,
        requestedBy: UserId,
        signatureImageBase64: String
    ) {
        val entity = employeeRepository.findByIdAndStudioId(employeeId.value, studioId.value)
            ?: throw EntityNotFoundException("Pracownik nie został znaleziony")

        val rawBytes = try {
            Base64.getDecoder().decode(signatureImageBase64)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Obraz podpisu nie jest poprawnym base64")
        }

        val normalizedPng = signatureImageProcessor.normalizeToTransparentPng(rawBytes)
        try {
            val s3Key = buildSignatureS3Key(studioId.value, employeeId.value)
            s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType("image/png")
                    .build(),
                RequestBody.fromBytes(normalizedPng)
            )

            // Delete old signature from S3 if it differs from new key (shouldn't happen, but defensive)
            entity.signatureS3Key?.let { oldKey ->
                if (oldKey != s3Key) {
                    runCatching {
                        s3Client.deleteObject(
                            DeleteObjectRequest.builder().bucket(bucketName).key(oldKey).build()
                        )
                    }
                }
            }

            entity.signatureS3Key = s3Key
            entity.updatedBy = requestedBy.value
            entity.updatedAt = Instant.now()
            employeeRepository.save(entity)

            logger.info("Employee signature saved: employeeId={} studioId={} s3Key={}", employeeId, studioId, s3Key)
        } finally {
            signatureImageProcessor.wipe(normalizedPng)
        }
    }

    @Transactional
    fun deleteSignature(studioId: StudioId, employeeId: EmployeeId, requestedBy: UserId) {
        val entity = employeeRepository.findByIdAndStudioId(employeeId.value, studioId.value)
            ?: throw EntityNotFoundException("Pracownik nie został znaleziony")

        val s3Key = entity.signatureS3Key
            ?: throw ValidationException("Pracownik nie ma skonfigurowanego podpisu")

        runCatching {
            s3Client.deleteObject(
                DeleteObjectRequest.builder().bucket(bucketName).key(s3Key).build()
            )
        }.onFailure { e ->
            logger.warn("Failed to delete signature from S3: key={} error={}", s3Key, e.message)
        }

        entity.signatureS3Key = null
        entity.updatedBy = requestedBy.value
        entity.updatedAt = Instant.now()
        employeeRepository.save(entity)

        logger.info("Employee signature deleted: employeeId={} studioId={}", employeeId, studioId)
    }

    fun getSignaturePresignedUrl(studioId: StudioId, employeeId: EmployeeId): String {
        val entity = employeeRepository.findByIdAndStudioId(employeeId.value, studioId.value)
            ?: throw EntityNotFoundException("Pracownik nie został znaleziony")
        val s3Key = entity.signatureS3Key
            ?: throw EntityNotFoundException("Pracownik nie ma skonfigurowanego podpisu")

        return s3Presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(PRESIGN_DURATION)
                .getObjectRequest(
                    GetObjectRequest.builder().bucket(bucketName).key(s3Key).build()
                )
                .build()
        ).url().toString()
    }

    fun downloadSignatureBytes(studioId: StudioId, employeeId: EmployeeId): ByteArray? {
        val entity = employeeRepository.findByIdAndStudioId(employeeId.value, studioId.value)
            ?: return null
        val s3Key = entity.signatureS3Key ?: return null
        return runCatching {
            s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucketName).key(s3Key).build()
            ).asByteArray()
        }.getOrNull()
    }

    private fun buildSignatureS3Key(studioId: UUID, employeeId: UUID): String =
        "$studioId/employees/$employeeId/signature.png"
}
