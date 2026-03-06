package pl.detailing.crm.checkin.qr

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitId
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Handles S3 operations for QR-based checkin photo uploads.
 *
 * Storage pattern:
 * - Temporary : temp/uploads/{tenantId}/{checkinId}/{photoId}.{ext}
 * - Final     : {tenantId}/visits/{visitId}/photos/checkin_{photoId}_{ts}.{ext}
 *
 * Retention:
 * Every uploaded object is tagged with `delete-after=<epoch-seconds>`.
 * An AWS S3 Lifecycle Rule matching that tag deletes objects automatically
 * after 3 hours — no scheduled cleanup code needed on the Spring side.
 *
 * Real-time sync:
 * After each successful S3 upload the service publishes a message to the
 * Redis channel "checkin:photo-uploaded". A separate MessageListener
 * picks it up and forwards it to the matching WebSocket topic.
 */
@Service
class CheckinPhotoService(
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String,
    @Value("\${checkin.upload-token-ttl-hours:3}") private val tokenTtlHours: Long
) {
    companion object {
        private val logger = LoggerFactory.getLogger(CheckinPhotoService::class.java)
        private val PRESIGNED_DOWNLOAD_DURATION = Duration.ofMinutes(15)
        private val ALLOWED_CONTENT_TYPES = setOf("image/jpeg", "image/jpg", "image/png", "image/webp")
        private const val MAX_FILE_SIZE = 10L * 1024 * 1024 // 10 MB
        const val REDIS_PHOTO_UPLOADED_CHANNEL = "checkin:photo-uploaded"

        fun buildTempPrefix(tenantId: String, checkinId: String): String =
            "temp/uploads/$tenantId/$checkinId/"

        fun buildTempKey(tenantId: String, checkinId: String, photoId: UUID, extension: String): String =
            "${buildTempPrefix(tenantId, checkinId)}$photoId.$extension"

        private fun contentTypeToExtension(ct: String): String = when (ct.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
    }

    // -------------------------------------------------
    // Mobile upload
    // -------------------------------------------------

    /**
     * Upload a photo file received from mobile directly to S3 temp storage.
     * Tags each object with `delete-after` epoch timestamp so the AWS Lifecycle
     * Rule can clean it up automatically if the checkin is never finalised.
     * Publishes a Redis Pub/Sub notification so the PC browser is updated in real time.
     */
    suspend fun uploadPhoto(
        metadata: UploadContextMetadata,
        fileName: String,
        contentType: String,
        fileBytes: ByteArray
    ): UploadedPhotoResult = withContext(Dispatchers.IO) {
        if (contentType.lowercase() !in ALLOWED_CONTENT_TYPES) {
            throw ValidationException(
                "Niedozwolony typ pliku. Dozwolone: ${ALLOWED_CONTENT_TYPES.joinToString()}"
            )
        }
        if (fileBytes.size > MAX_FILE_SIZE) {
            throw ValidationException(
                "Plik przekracza maksymalny rozmiar (${MAX_FILE_SIZE / 1024 / 1024} MB)"
            )
        }

        val photoId = UUID.randomUUID()
        val extension = contentTypeToExtension(contentType)
        val s3Key = buildTempKey(metadata.tenantId, metadata.checkinId, photoId, extension)

        // delete-after tag: epoch-seconds 3 h from now  →  matched by Lifecycle Rule
        val deleteAfter = Instant.now().plusSeconds(tokenTtlHours * 3600).epochSecond.toString()
        val taggingHeader = buildTaggingHeader(
            "delete-after" to deleteAfter,
            "checkin-id" to metadata.checkinId,
            "tenant-id" to metadata.tenantId
        )

        val putRequest = PutObjectRequest.builder()
            .bucket(bucketName)
            .key(s3Key)
            .contentType(contentType)
            .contentLength(fileBytes.size.toLong())
            .tagging(taggingHeader)
            .build()

        s3Client.putObject(putRequest, RequestBody.fromBytes(fileBytes))
        logger.info("Uploaded QR checkin photo: key=$s3Key, size=${fileBytes.size}B")

        publishPhotoUploadedEvent(
            tenantId = metadata.tenantId,
            checkinId = metadata.checkinId,
            photoId = photoId.toString(),
            fileName = fileName,
            s3Key = s3Key
        )

        UploadedPhotoResult(
            photoId = photoId.toString(),
            fileName = fileName,
            s3Key = s3Key,
            uploadedAt = Instant.now()
        )
    }

    // -------------------------------------------------
    // Finalization (called when checkin form is submitted on PC)
    // -------------------------------------------------

    /**
     * Move all photos uploaded via QR for a specific checkin from
     * temp/uploads/{tenantId}/{checkinId}/ to the final visit storage path.
     *
     * Called inside CreateVisitFromReservationHandler after the visit is persisted.
     */
    suspend fun finalizePhotos(
        tenantId: String,
        checkinId: String,
        visitId: VisitId
    ): List<FinalizedCheckinPhoto> = withContext(Dispatchers.IO) {
        val prefix = buildTempPrefix(tenantId, checkinId)
        val objects = listObjectsUnderPrefix(prefix)

        if (objects.isEmpty()) {
            logger.info("No QR-uploaded photos to finalize for checkin=$checkinId")
            return@withContext emptyList()
        }

        logger.info("Finalizing ${objects.size} QR photo(s) for checkin=$checkinId → visit=${visitId.value}")
        val finalized = mutableListOf<FinalizedCheckinPhoto>()

        for (s3Obj in objects) {
            val sourceKey = s3Obj
            val baseName = sourceKey.substringAfterLast('/')
            val extension = baseName.substringAfterLast('.', "jpg")
            val photoId = baseName.substringBeforeLast('.')
            val timestamp = Instant.now().toEpochMilli()
            val finalKey = "$tenantId/visits/${visitId.value}/photos/checkin_${photoId}_$timestamp.$extension"

            try {
                s3Client.copyObject(
                    CopyObjectRequest.builder()
                        .sourceBucket(bucketName)
                        .sourceKey(sourceKey)
                        .destinationBucket(bucketName)
                        .destinationKey(finalKey)
                        .build()
                )

                s3Client.deleteObject(
                    DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(sourceKey)
                        .build()
                )

                logger.info("Finalized QR photo: $sourceKey → $finalKey")
                finalized.add(
                    FinalizedCheckinPhoto(
                        photoId = UUID.randomUUID(),
                        fileId = finalKey,
                        fileName = baseName
                    )
                )
            } catch (e: Exception) {
                // Log but do not abort — the visit was already saved.
                logger.error("Failed to finalize QR photo $sourceKey: ${e.message}", e)
            }
        }

        finalized
    }

    // -------------------------------------------------
    // Helpers
    // -------------------------------------------------

    fun generateDownloadUrl(s3Key: String): String {
        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(PRESIGNED_DOWNLOAD_DURATION)
            .getObjectRequest(
                GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build()
            )
            .build()
        return s3Presigner.presignGetObject(presignRequest).url().toString()
    }

    private fun listObjectsUnderPrefix(prefix: String): List<String> {
        val response = s3Client.listObjectsV2(
            ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .build()
        )
        return response.contents().map { it.key() }
    }

    private fun publishPhotoUploadedEvent(
        tenantId: String,
        checkinId: String,
        photoId: String,
        fileName: String,
        s3Key: String
    ) {
        try {
            val message = objectMapper.writeValueAsString(
                mapOf(
                    "tenantId" to tenantId,
                    "checkinId" to checkinId,
                    "photoId" to photoId,
                    "fileName" to fileName,
                    "s3Key" to s3Key,
                    "uploadedAt" to Instant.now().toString()
                )
            )
            redisTemplate.convertAndSend(REDIS_PHOTO_UPLOADED_CHANNEL, message)
            logger.debug("Published Redis photo-uploaded event: checkin=$checkinId photoId=$photoId")
        } catch (e: Exception) {
            // Notification failure must never abort the upload itself
            logger.warn("Failed to publish photo upload notification: ${e.message}")
        }
    }

    /** Build the URL-encoded tagging string required by S3 PutObjectRequest. */
    private fun buildTaggingHeader(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
        }
}

data class UploadedPhotoResult(
    val photoId: String,
    val fileName: String,
    val s3Key: String,
    val uploadedAt: Instant
)

data class FinalizedCheckinPhoto(
    val photoId: UUID,
    val fileId: String,
    val fileName: String
)
