package pl.detailing.crm.user.signature

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.signing.infrastructure.SignatureImageProcessor
import pl.detailing.crm.user.infrastructure.UserRepository
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
class UserSignatureService(
    private val userRepository: UserRepository,
    private val signatureImageProcessor: SignatureImageProcessor,
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private val PRESIGN_DURATION = Duration.ofMinutes(10)
    }

    data class SignatureStatus(val hasSignature: Boolean, val url: String?)

    @Transactional
    fun save(studioId: StudioId, userId: UserId, signatureImageBase64: String) {
        val entity = userRepository.findByIdAndStudioId(userId.value, studioId.value)
            ?: throw EntityNotFoundException("Użytkownik nie został znaleziony")

        val rawBytes = try {
            Base64.getDecoder().decode(signatureImageBase64)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Obraz podpisu nie jest poprawnym base64")
        }

        val normalizedPng = signatureImageProcessor.normalizeToTransparentPng(rawBytes)
        try {
            val s3Key = buildS3Key(studioId.value, userId.value)
            s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType("image/png")
                    .build(),
                RequestBody.fromBytes(normalizedPng)
            )

            entity.signatureS3Key = s3Key
            userRepository.save(entity)

            logger.info("User signature saved: userId={} studioId={}", userId, studioId)
        } finally {
            signatureImageProcessor.wipe(normalizedPng)
        }
    }

    @Transactional
    fun delete(studioId: StudioId, userId: UserId) {
        val entity = userRepository.findByIdAndStudioId(userId.value, studioId.value)
            ?: throw EntityNotFoundException("Użytkownik nie został znaleziony")

        val s3Key = entity.signatureS3Key
            ?: throw ValidationException("Użytkownik nie ma skonfigurowanego podpisu")

        runCatching {
            s3Client.deleteObject(
                DeleteObjectRequest.builder().bucket(bucketName).key(s3Key).build()
            )
        }.onFailure { e ->
            logger.warn("Failed to delete user signature from S3: key={} error={}", s3Key, e.message)
        }

        entity.signatureS3Key = null
        userRepository.save(entity)

        logger.info("User signature deleted: userId={} studioId={}", userId, studioId)
    }

    fun getStatus(studioId: StudioId, userId: UserId): SignatureStatus {
        val entity = userRepository.findByIdAndStudioId(userId.value, studioId.value)
            ?: throw EntityNotFoundException("Użytkownik nie został znaleziony")

        val s3Key = entity.signatureS3Key ?: return SignatureStatus(hasSignature = false, url = null)

        val url = runCatching {
            s3Presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                    .signatureDuration(PRESIGN_DURATION)
                    .getObjectRequest(
                        GetObjectRequest.builder().bucket(bucketName).key(s3Key).build()
                    )
                    .build()
            ).url().toString()
        }.getOrNull()

        return SignatureStatus(hasSignature = true, url = url)
    }

    fun downloadBytes(studioId: StudioId, userId: UserId): ByteArray? {
        val entity = userRepository.findByIdAndStudioId(userId.value, studioId.value) ?: return null
        val s3Key = entity.signatureS3Key ?: return null
        return runCatching {
            s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucketName).key(s3Key).build()
            ).asByteArray()
        }.getOrNull()
    }

    private fun buildS3Key(studioId: UUID, userId: UUID): String =
        "$studioId/users/$userId/signature.png"
}
