package pl.detailing.crm.visit.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.time.Duration
import java.util.*

/**
 * Service for storing and retrieving damage map images on AWS S3.
 *
 * Storage Path Pattern:
 * - Damage Maps: {studioId}/visits/{visitId}/damage-map.jpg
 *
 * Upload Flow:
 * 1. Backend generates the damage map image
 * 2. Backend uploads directly to S3 (no presigned URL needed)
 *
 * Download Flow:
 * 1. Backend generates presigned GET URL (10-minute expiry)
 * 2. Frontend/PDF generator downloads image directly from S3
 */
@Service
class S3DamageMapStorageService(
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String
) {

    companion object {
        private val logger = LoggerFactory.getLogger(S3DamageMapStorageService::class.java)
        private val DOWNLOAD_URL_DURATION = Duration.ofMinutes(10)
    }

    /**
     * Upload a damage map image to S3.
     *
     * @param studioId The studio ID
     * @param visitId The visit ID
     * @param imageBytes The JPG image bytes
     * @return The S3 key where the image was stored
     */
    suspend fun uploadDamageMap(
        studioId: UUID,
        visitId: UUID,
        imageBytes: ByteArray
    ): String = withContext(Dispatchers.IO) {
        val s3Key = buildDamageMapS3Key(studioId, visitId)

        try {
            val putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .contentType("image/jpeg")
                .contentLength(imageBytes.size.toLong())
                .metadata(mapOf(
                    "studio-id" to studioId.toString(),
                    "visit-id" to visitId.toString()
                ))
                .build()

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(imageBytes))

            logger.info("Successfully uploaded damage map to S3: $s3Key (${imageBytes.size} bytes)")

            return@withContext s3Key

        } catch (e: Exception) {
            logger.error("Failed to upload damage map to S3: $s3Key", e)
            throw IllegalStateException("Failed to upload damage map to S3: ${e.message}", e)
        }
    }

    /**
     * Generate a presigned URL for downloading/viewing a damage map image.
     *
     * @param s3Key The S3 key of the damage map
     * @return Presigned URL valid for 10 minutes
     */
    fun generateDownloadUrl(s3Key: String): String {
        val getObjectRequest = GetObjectRequest.builder()
            .bucket(bucketName)
            .key(s3Key)
            .build()

        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(DOWNLOAD_URL_DURATION)
            .getObjectRequest(getObjectRequest)
            .build()

        val presignedRequest = s3Presigner.presignGetObject(presignRequest)
        return presignedRequest.url().toString()
    }

    /**
     * Build S3 key for a damage map image.
     *
     * Pattern: {studioId}/visits/{visitId}/damage-map.jpg
     */
    fun buildDamageMapS3Key(studioId: UUID, visitId: UUID): String {
        return "$studioId/visits/$visitId/damage-map.jpg"
    }
}
