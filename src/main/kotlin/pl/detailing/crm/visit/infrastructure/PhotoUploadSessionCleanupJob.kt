package pl.detailing.crm.visit.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import java.time.Instant

/**
 * Scheduled job for cleaning up expired photo upload sessions
 *
 * Runs every hour to:
 * 1. Find expired unclaimed sessions
 * 2. Delete temporary photos from S3
 * 3. Delete temporary photo records from DB
 * 4. Delete expired sessions from DB
 *
 * This prevents orphaned files in S3 when users abandon check-in forms
 */
@Service
class PhotoUploadSessionCleanupJob(
    private val photoUploadSessionRepository: PhotoUploadSessionRepository,
    private val temporaryPhotoRepository: TemporaryPhotoRepository,
    private val s3Client: S3Client,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String
) {

    companion object {
        private val logger = LoggerFactory.getLogger(PhotoUploadSessionCleanupJob::class.java)
    }

    /**
     * Cleanup expired sessions
     * Runs every hour at 5 minutes past the hour (e.g., 1:05, 2:05, etc.)
     */
    @Scheduled(cron = "0 5 * * * *")
    @Transactional
    fun cleanupExpiredSessions() = runBlocking {
        withContext(Dispatchers.IO) {
            val startTime = Instant.now()
            logger.info("Starting photo upload session cleanup at $startTime")

            try {
                val expiredSessions = photoUploadSessionRepository.findExpiredUnclaimedSessions(Instant.now())

                if (expiredSessions.isEmpty()) {
                    logger.info("No expired sessions to clean up")
                    return@withContext
                }

                logger.info("Found ${expiredSessions.size} expired unclaimed sessions to clean up")

                var deletedSessions = 0
                var deletedPhotos = 0
                var deletedS3Files = 0
                var failedS3Deletions = 0

                for (session in expiredSessions) {
                    try {
                        // Find all temporary photos for this session
                        val tempPhotos = temporaryPhotoRepository.findBySessionId(session.id)

                        logger.debug("Session ${session.id}: found ${tempPhotos.size} temporary photos")

                        // Delete each photo
                        for (photo in tempPhotos) {
                            if (!photo.claimed) {
                                // Delete from S3
                                try {
                                    deleteFromS3(photo.s3Key)
                                    deletedS3Files++
                                    logger.debug("Deleted S3 file: ${photo.s3Key}")
                                } catch (e: Exception) {
                                    logger.error("Failed to delete S3 file ${photo.s3Key}: ${e.message}")
                                    failedS3Deletions++
                                    // Continue - we'll delete the DB record anyway to prevent orphans
                                }

                                // Delete from DB
                                try {
                                    temporaryPhotoRepository.delete(photo)
                                    deletedPhotos++
                                } catch (e: Exception) {
                                    logger.error("Failed to delete temporary photo ${photo.id}: ${e.message}", e)
                                }
                            }
                        }

                        // Delete session from DB
                        try {
                            photoUploadSessionRepository.delete(session)
                            deletedSessions++
                            logger.debug("Deleted session ${session.id}")
                        } catch (e: Exception) {
                            logger.error("Failed to delete session ${session.id}: ${e.message}", e)
                        }

                    } catch (e: Exception) {
                        logger.error("Error processing session ${session.id}: ${e.message}", e)
                        // Continue with next session
                    }
                }

                val duration = java.time.Duration.between(startTime, Instant.now())

                logger.info(
                    "Photo upload session cleanup completed in ${duration.toMillis()}ms: " +
                            "deleted $deletedSessions sessions, " +
                            "$deletedPhotos DB photo records, " +
                            "$deletedS3Files S3 files " +
                            "(failed S3 deletions: $failedS3Deletions)"
                )

            } catch (e: Exception) {
                logger.error("Photo upload session cleanup failed: ${e.message}", e)
            }
        }
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
