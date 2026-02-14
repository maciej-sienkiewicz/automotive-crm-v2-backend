package pl.detailing.crm.visit.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.*
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * Service for managing photo upload sessions
 *
 * Upload Flow:
 * 1. Create session -> get sessionId + token
 * 2. Request presigned URL for each photo -> upload directly to S3
 * 3. Submit visit with photoIds -> photos linked to visit and moved to final location
 * 4. Cleanup job removes expired unclaimed sessions
 *
 * Storage Pattern:
 * - Temporary: temp/{studioId}/sessions/{sessionId}/{photoId}.{ext}
 * - Final: {studioId}/visits/{visitId}/photos/{photoType}_{timestamp}.{ext}
 */
@Service
class PhotoSessionService(
    private val photoUploadSessionRepository: PhotoUploadSessionRepository,
    private val temporaryPhotoRepository: TemporaryPhotoRepository,
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String,
    @Value("\${photo-upload.session-ttl-hours:2}") private val sessionTtlHours: Long,
    @Value("\${photo-upload.max-photos-per-session:20}") private val maxPhotosPerSession: Int
) {

    companion object {
        private val logger = LoggerFactory.getLogger(PhotoSessionService::class.java)
        private val PRESIGNED_URL_DURATION = Duration.ofMinutes(15)
        private val SECURE_RANDOM = SecureRandom()

        private val ALLOWED_CONTENT_TYPES = setOf(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp"
        )

        private const val MAX_FILE_SIZE = 10L * 1024 * 1024 // 10MB
    }

    /**
     * Create a new photo upload session
     */
    suspend fun createSession(
        studioId: StudioId,
        appointmentId: AppointmentId,
        userId: UserId
    ): PhotoUploadSessionEntity = withContext(Dispatchers.IO) {
        logger.info("Creating photo upload session for appointment ${appointmentId.value}, studio ${studioId.value}")

        val session = PhotoUploadSessionEntity(
            id = UUID.randomUUID(),
            studioId = studioId.value,
            appointmentId = appointmentId.value,
            token = generateSecureToken(),
            expiresAt = Instant.now().plusSeconds(sessionTtlHours * 3600),
            createdAt = Instant.now()
        )

        photoUploadSessionRepository.save(session)

        logger.info("Created photo upload session ${session.id}")
        session
    }

    /**
     * Generate a presigned upload URL for a photo
     */
    suspend fun generateUploadUrl(
        sessionId: UUID,
        studioId: StudioId,
        fileName: String,
        photoType: PhotoType,
        contentType: String,
        fileSize: Long,
        sessionToken: String
    ): UploadUrlResult = withContext(Dispatchers.IO) {
        // Validate session
        val session = photoUploadSessionRepository.findByIdAndStudioId(sessionId, studioId.value)
            ?: throw EntityNotFoundException("Upload session not found")

        if (session.token != sessionToken) {
            throw ForbiddenException("Invalid session token")
        }

        if (session.isExpired()) {
            throw ValidationException("Upload session expired")
        }

        if (session.isClaimed()) {
            throw ValidationException("Upload session already claimed")
        }

        // Validate file
        if (contentType.lowercase() !in ALLOWED_CONTENT_TYPES) {
            throw ValidationException("Invalid content type. Allowed: ${ALLOWED_CONTENT_TYPES.joinToString()}")
        }

        if (fileSize > MAX_FILE_SIZE) {
            throw ValidationException("File size exceeds maximum allowed (${MAX_FILE_SIZE / 1024 / 1024}MB)")
        }

        // Check photo count limit
        val existingPhotos = temporaryPhotoRepository.findUnclaimedBySessionId(sessionId)
        if (existingPhotos.size >= maxPhotosPerSession) {
            throw ValidationException("Maximum $maxPhotosPerSession photos per session exceeded")
        }

        // Generate photo ID and S3 key
        val photoId = UUID.randomUUID()
        val extension = when (contentType) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }

        val s3Key = buildTempPhotoS3Key(studioId.value, sessionId, photoId, extension)

        // Generate presigned URL
        val putObjectRequest = PutObjectRequest.builder()
            .bucket(bucketName)
            .key(s3Key)
            .contentType(contentType)
            .contentLength(fileSize)
            .build()

        val presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(PRESIGNED_URL_DURATION)
            .putObjectRequest(putObjectRequest)
            .build()

        val presignedRequest = s3Presigner.presignPutObject(presignRequest)
        val uploadUrl = presignedRequest.url().toString()

        // Save temporary photo record
        val tempPhoto = TemporaryPhotoEntity(
            id = photoId,
            sessionId = sessionId,
            photoType = photoType.name,
            s3Key = s3Key,
            fileName = fileName,
            fileSize = fileSize,
            contentType = contentType,
            uploadedAt = Instant.now(),
            claimed = false
        )
        temporaryPhotoRepository.save(tempPhoto)

        logger.info("Generated upload URL for photo $photoId in session $sessionId")

        UploadUrlResult(
            photoId = photoId,
            uploadUrl = uploadUrl,
            expiresAt = Instant.now().plus(PRESIGNED_URL_DURATION)
        )
    }

    /**
     * Get all photos in a session
     */
    suspend fun getSessionPhotos(
        sessionId: UUID,
        studioId: StudioId
    ): List<SessionPhotoInfo> = withContext(Dispatchers.IO) {
        // Validate session exists
        photoUploadSessionRepository.findByIdAndStudioId(sessionId, studioId.value)
            ?: throw EntityNotFoundException("Upload session not found")

        val photos = temporaryPhotoRepository.findBySessionId(sessionId)

        photos.map { photo ->
            // Generate download URL for preview
            val downloadUrl = generateDownloadUrl(photo.s3Key)

            SessionPhotoInfo(
                id = photo.id,
                photoType = PhotoType.valueOf(photo.photoType),
                fileName = photo.fileName,
                fileSize = photo.fileSize,
                uploadedAt = photo.uploadedAt,
                thumbnailUrl = downloadUrl
            )
        }
    }

    /**
     * Delete a photo from a session
     */
    suspend fun deleteSessionPhoto(
        sessionId: UUID,
        photoId: UUID,
        studioId: StudioId
    ): Unit = withContext(Dispatchers.IO) {
        // Validate session
        val session = photoUploadSessionRepository.findByIdAndStudioId(sessionId, studioId.value)
            ?: throw EntityNotFoundException("Upload session not found")

        if (session.isClaimed()) {
            throw ValidationException("Cannot delete photos from claimed session")
        }

        // Find photo
        val photo = temporaryPhotoRepository.findByIdAndSessionId(photoId, sessionId)
            ?: throw EntityNotFoundException("Photo not found in session")

        if (photo.claimed) {
            throw ValidationException("Cannot delete claimed photo")
        }

        // Delete from S3
        try {
            deleteFromS3(photo.s3Key)
        } catch (e: Exception) {
            logger.error("Failed to delete photo ${photo.s3Key} from S3: ${e.message}", e)
            // Continue with DB deletion even if S3 deletion fails
        }

        // Delete from DB
        temporaryPhotoRepository.delete(photo)

        logger.info("Deleted photo $photoId from session $sessionId")
    }

    /**
     * Move photos from temp location to final visit location
     * Called by CreateVisitFromReservationHandler
     */
    suspend fun claimPhotosForVisit(
        photoIds: List<UUID>,
        visitId: VisitId,
        studioId: StudioId
    ): List<ClaimedPhoto> = withContext(Dispatchers.IO) {
        if (photoIds.isEmpty()) {
            return@withContext emptyList()
        }

        val claimedPhotos = mutableListOf<ClaimedPhoto>()

        for (photoId in photoIds) {
            val tempPhoto = temporaryPhotoRepository.findById(photoId).orElse(null)
                ?: throw EntityNotFoundException("Photo $photoId not found")

            if (tempPhoto.claimed) {
                throw ValidationException("Photo $photoId already claimed")
            }

            // Validate session
            val session = photoUploadSessionRepository.findById(tempPhoto.sessionId).orElse(null)
                ?: throw EntityNotFoundException("Upload session not found for photo $photoId")

            if (session.isExpired()) {
                throw ValidationException("Upload session expired")
            }

            // Copy file from temp to final location
            val tempKey = tempPhoto.s3Key
            val extension = tempKey.substringAfterLast('.')
            val photoType = PhotoType.valueOf(tempPhoto.photoType)
            val timestamp = Instant.now().toEpochMilli()
            val finalKey = "${studioId.value}/visits/${visitId.value}/photos/${photoType}_${timestamp}.$extension"

            try {
                copyInS3(tempKey, finalKey)
                logger.info("Copied photo from $tempKey to $finalKey")
            } catch (e: Exception) {
                logger.error("Failed to copy photo in S3: ${e.message}", e)
                throw IllegalStateException("Failed to copy photo in S3: ${e.message}", e)
            }

            // Mark as claimed
            tempPhoto.claimed = true
            temporaryPhotoRepository.save(tempPhoto)

            claimedPhotos.add(ClaimedPhoto(
                id = photoId,
                photoType = photoType,
                fileId = finalKey,
                fileName = tempPhoto.fileName
            ))

            // Clean up temp file asynchronously (best effort)
            try {
                deleteFromS3(tempKey)
            } catch (e: Exception) {
                logger.warn("Failed to delete temp photo $tempKey: ${e.message}")
                // Continue - cleanup job will handle it
            }
        }

        // Mark session as claimed
        if (claimedPhotos.isNotEmpty()) {
            val session = photoUploadSessionRepository.findById(
                temporaryPhotoRepository.findById(photoIds.first()).get().sessionId
            ).get()

            session.claimedAt = Instant.now()
            session.visitId = visitId.value
            photoUploadSessionRepository.save(session)

            logger.info("Claimed ${claimedPhotos.size} photos for visit ${visitId.value}")
        }

        claimedPhotos
    }

    /**
     * Build S3 key for temporary photo
     */
    private fun buildTempPhotoS3Key(studioId: UUID, sessionId: UUID, photoId: UUID, extension: String): String {
        return "temp/$studioId/sessions/$sessionId/$photoId.$extension"
    }

    /**
     * Generate secure random token
     */
    private fun generateSecureToken(): String {
        val bytes = ByteArray(32)
        SECURE_RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Generate presigned download URL
     */
    private fun generateDownloadUrl(s3Key: String): String {
        val getObjectRequest = GetObjectRequest.builder()
            .bucket(bucketName)
            .key(s3Key)
            .build()

        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(PRESIGNED_URL_DURATION)
            .getObjectRequest(getObjectRequest)
            .build()

        val presignedRequest = s3Presigner.presignGetObject(presignRequest)
        return presignedRequest.url().toString()
    }

    /**
     * Copy object within S3
     */
    private fun copyInS3(sourceKey: String, destinationKey: String) {
        val copyRequest = CopyObjectRequest.builder()
            .sourceBucket(bucketName)
            .sourceKey(sourceKey)
            .destinationBucket(bucketName)
            .destinationKey(destinationKey)
            .build()

        s3Client.copyObject(copyRequest)
    }

    /**
     * Delete object from S3
     */
    private fun deleteFromS3(s3Key: String) {
        val deleteRequest = DeleteObjectRequest.builder()
            .bucket(bucketName)
            .key(s3Key)
            .build()

        s3Client.deleteObject(deleteRequest)
    }
}

data class UploadUrlResult(
    val photoId: UUID,
    val uploadUrl: String,
    val expiresAt: Instant
)

data class SessionPhotoInfo(
    val id: UUID,
    val photoType: PhotoType,
    val fileName: String,
    val fileSize: Long,
    val uploadedAt: Instant,
    val thumbnailUrl: String
)

data class ClaimedPhoto(
    val id: UUID,
    val photoType: PhotoType,
    val fileId: String,
    val fileName: String
)
