package pl.detailing.crm.protocol.infrastructure

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
 * Service for generating presigned URLs for protocol document storage on S3.
 *
 * Upload Flow:
 * 1. Backend generates presigned PUT URL
 * 2. Frontend uploads PDF directly to S3
 *
 * Download Flow:
 * 1. Backend generates presigned GET URL (10-minute expiry)
 * 2. Frontend downloads/views PDF directly from S3
 *
 * Storage Path Patterns:
 * - Templates: {studioId}/protocols/templates/{templateId}.pdf
 * - Filled PDFs: {studioId}/protocols/visits/{visitId}/filled/{protocolId}.pdf
 * - Signed PDFs: {studioId}/protocols/visits/{visitId}/signed/{protocolId}.pdf
 * - Signatures: {studioId}/protocols/visits/{visitId}/signatures/{protocolId}.png
 */
@Service
class S3ProtocolStorageService(
    private val s3Presigner: S3Presigner,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String
) {

    companion object {
        private val UPLOAD_URL_DURATION = Duration.ofMinutes(15)
        private val DOWNLOAD_URL_DURATION = Duration.ofMinutes(10)
    }

    /**
     * Generate a presigned URL for uploading a protocol template PDF.
     */
    fun generateTemplateUploadUrl(studioId: UUID, templateId: UUID): String {
        val s3Key = buildTemplateS3Key(studioId, templateId)
        return generateUploadUrl(s3Key, "application/pdf")
    }

    /**
     * Generate a presigned URL for uploading a filled protocol PDF.
     */
    fun generateFilledPdfUploadUrl(studioId: UUID, visitId: UUID, protocolId: UUID): String {
        val s3Key = buildFilledPdfS3Key(studioId, visitId, protocolId)
        return generateUploadUrl(s3Key, "application/pdf")
    }

    /**
     * Generate a presigned URL for uploading a signed protocol PDF.
     */
    fun generateSignedPdfUploadUrl(studioId: UUID, visitId: UUID, protocolId: UUID): String {
        val s3Key = buildSignedPdfS3Key(studioId, visitId, protocolId)
        return generateUploadUrl(s3Key, "application/pdf")
    }

    /**
     * Generate a presigned URL for uploading a signature image.
     */
    fun generateSignatureImageUploadUrl(studioId: UUID, visitId: UUID, protocolId: UUID): String {
        val s3Key = buildSignatureImageS3Key(studioId, visitId, protocolId)
        return generateUploadUrl(s3Key, "image/png")
    }

    /**
     * Generate a presigned URL for downloading/viewing any PDF.
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
     * Build S3 key for a protocol template.
     */
    fun buildTemplateS3Key(studioId: UUID, templateId: UUID): String {
        return "$studioId/protocols/templates/$templateId.pdf"
    }

    /**
     * Build S3 key for a filled protocol PDF.
     */
    fun buildFilledPdfS3Key(studioId: UUID, visitId: UUID, protocolId: UUID): String {
        return "$studioId/protocols/visits/$visitId/filled/$protocolId.pdf"
    }

    /**
     * Build S3 key for a signed protocol PDF.
     */
    fun buildSignedPdfS3Key(studioId: UUID, visitId: UUID, protocolId: UUID): String {
        return "$studioId/protocols/visits/$visitId/signed/$protocolId.pdf"
    }

    /**
     * Build S3 key for a signature image.
     */
    fun buildSignatureImageS3Key(studioId: UUID, visitId: UUID, protocolId: UUID): String {
        return "$studioId/protocols/visits/$visitId/signatures/$protocolId.png"
    }

    /**
     * Generate a presigned upload URL for a given S3 key and content type.
     */
    private fun generateUploadUrl(s3Key: String, contentType: String): String {
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
}
