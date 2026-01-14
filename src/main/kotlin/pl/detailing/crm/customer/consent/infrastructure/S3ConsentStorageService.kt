package pl.detailing.crm.customer.consent.infrastructure

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.time.Duration
import java.util.*

/**
 * Service for generating presigned URLs for consent document storage on S3.
 *
 * Upload: The frontend receives a presigned URL to upload the PDF directly to S3.
 * Download: The frontend receives a short-lived presigned URL to download/view the PDF.
 *
 * Storage Path Pattern: {bucket}/{studioId}/consents/templates/{definitionSlug}_v{version}.pdf
 */
@Service
class S3ConsentStorageService(
    private val s3Presigner: S3Presigner,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String
) {

    companion object {
        private val UPLOAD_URL_DURATION = Duration.ofMinutes(15)
        private val DOWNLOAD_URL_DURATION = Duration.ofMinutes(10)
    }

    /**
     * Generate a presigned URL for uploading a consent template PDF.
     *
     * @param studioId The studio/tenant ID
     * @param definitionSlug The consent definition slug (e.g., "rodo")
     * @param version The template version number
     * @return Presigned upload URL valid for 15 minutes
     */
    fun generateUploadUrl(
        studioId: UUID,
        definitionSlug: String,
        version: Int
    ): String {
        val s3Key = buildS3Key(studioId, definitionSlug, version)

        val putObjectRequest = PutObjectRequest.builder()
            .bucket(bucketName)
            .key(s3Key)
            .contentType("application/pdf")
            .build()

        val presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(UPLOAD_URL_DURATION)
            .putObjectRequest(putObjectRequest)
            .build()

        val presignedRequest = s3Presigner.presignPutObject(presignRequest)
        return presignedRequest.url().toString()
    }

    /**
     * Generate a presigned URL for downloading/viewing a consent template PDF.
     *
     * @param s3Key The S3 object key
     * @return Presigned download URL valid for 10 minutes
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
     * Build the S3 object key for a consent template.
     *
     * Pattern: {studioId}/consents/templates/{definitionSlug}_v{version}.pdf
     */
    fun buildS3Key(
        studioId: UUID,
        definitionSlug: String,
        version: Int
    ): String {
        return "$studioId/consents/templates/${definitionSlug}_v${version}.pdf"
    }
}
