package pl.detailing.crm.visit.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.DocumentType
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * Service for managing document storage on AWS S3
 *
 * Storage Pattern:
 * - Documents: {studioId}/customers/{customerId}/visits/{visitId}/{type}_{timestamp}.{extension}
 *
 * Security:
 * - All S3 objects are Private
 * - Presigned URLs generated with 15-minute expiry for downloads
 * - Presigned URLs generated with 15-minute expiry for uploads
 */
@Service
class DocumentStorageService(
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String
) {

    companion object {
        private val logger = LoggerFactory.getLogger(DocumentStorageService::class.java)
        private val DOWNLOAD_URL_DURATION = Duration.ofMinutes(15)
        private val UPLOAD_URL_DURATION = Duration.ofMinutes(15)
    }

    /**
     * Build S3 key for a document
     *
     * Pattern: {studioId}/customers/{customerId}/visits/{visitId}/{type}_{timestamp}.{extension}
     *
     * Examples:
     * - abc123/customers/customer-uuid/visits/visit-uuid/INTAKE_1704067200000.pdf
     * - abc123/customers/customer-uuid/visits/visit-uuid/DAMAGE_MAP_1704067200000.jpg
     */
    fun buildDocumentS3Key(
        studioId: UUID,
        customerId: UUID,
        visitId: UUID,
        documentType: DocumentType,
        extension: String = "pdf"
    ): String {
        val timestamp = Instant.now().toEpochMilli()
        return "$studioId/customers/$customerId/visits/$visitId/${documentType}_$timestamp.$extension"
    }

    /**
     * Upload a document to S3 directly
     *
     * @param s3Key The S3 key where the document will be stored
     * @param fileBytes The document file bytes
     * @param contentType The MIME content type (e.g., "application/pdf", "image/jpeg")
     * @param metadata Additional metadata to store with the document
     * @return The S3 key where the document was stored
     */
    suspend fun uploadDocument(
        s3Key: String,
        fileBytes: ByteArray,
        contentType: String,
        metadata: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        try {
            val putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .contentType(contentType)
                .contentLength(fileBytes.size.toLong())
                .metadata(metadata)
                .build()

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(fileBytes))

            logger.info("Successfully uploaded document to S3: $s3Key (${fileBytes.size} bytes)")

            return@withContext s3Key

        } catch (e: Exception) {
            logger.error("Failed to upload document to S3: $s3Key", e)
            throw IllegalStateException("Failed to upload document to S3: ${e.message}", e)
        }
    }

    /**
     * Generate a presigned URL for downloading/viewing a document
     * Valid for 15 minutes
     *
     * @param s3Key The S3 key of the document
     * @return Presigned URL string valid for 15 minutes
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
     * Generate a presigned URL for uploading a document
     * Valid for 15 minutes
     *
     * This allows the frontend to upload files directly to S3 without proxying through the backend
     *
     * @param s3Key The S3 key where the document will be stored
     * @param contentType The MIME content type
     * @return Presigned URL string valid for 15 minutes
     */
    fun generateUploadUrl(s3Key: String, contentType: String): String {
        val putObjectRequest = PutObjectRequest.builder()
            .bucket(bucketName)
            .key(s3Key)
            .contentType(contentType)
            .build()

        val presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(UPLOAD_URL_DURATION)
            .putObjectRequest(putObjectRequest)
            .build()

        val presignedRequest = s3Presigner.presignPutObject(presignRequest)
        return presignedRequest.url().toString()
    }

    /**
     * Delete a document from S3
     *
     * @param s3Key The S3 key of the document to delete
     */
    suspend fun deleteDocument(s3Key: String): Unit = withContext(Dispatchers.IO) {
        try {
            val deleteObjectRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build()

            s3Client.deleteObject(deleteObjectRequest)

            logger.info("Successfully deleted document from S3: $s3Key")

        } catch (e: Exception) {
            logger.error("Failed to delete document from S3: $s3Key", e)
            throw IllegalStateException("Failed to delete document from S3: ${e.message}", e)
        }
    }
}
