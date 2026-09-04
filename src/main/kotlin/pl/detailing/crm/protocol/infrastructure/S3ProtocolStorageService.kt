package pl.detailing.crm.protocol.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
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
 * - Filled PDFs: {studioId}/protocols/visits/{visitId}/filled/PPP_{visitNumber}_{version}_{protocolId}.pdf
 * - Signed PDFs: {studioId}/protocols/visits/{visitId}/signed/PPP_{visitNumber}_{version}_{protocolId}.pdf
 * - Signatures: {studioId}/protocols/visits/{visitId}/signatures/{protocolId}.png
 */
@Service
class S3ProtocolStorageService(
    private val s3Presigner: S3Presigner,
    private val s3Client: S3Client,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String
) {

    companion object {
        private val logger = LoggerFactory.getLogger(S3ProtocolStorageService::class.java)
        private val UPLOAD_URL_DURATION = Duration.ofMinutes(15)
        private val DOWNLOAD_URL_DURATION = Duration.ofMinutes(10)
    }

    /**
     * Generate a presigned URL for uploading a protocol template file (PDF or HTML).
     */
    fun generateTemplateUploadUrl(
        studioId: UUID,
        templateId: UUID,
        format: pl.detailing.crm.protocol.domain.ProtocolTemplateFormat =
            pl.detailing.crm.protocol.domain.ProtocolTemplateFormat.PDF
    ): String {
        val s3Key = buildTemplateS3Key(studioId, templateId, format)
        return generateUploadUrl(s3Key, format.contentType)
    }

    /**
     * Generate a presigned URL for uploading a filled protocol PDF.
     */
    fun generateFilledPdfUploadUrl(
        studioId: UUID,
        visitId: UUID,
        visitNumber: String,
        version: Int,
        protocolId: UUID
    ): String {
        val s3Key = buildFilledPdfS3Key(studioId, visitId, visitNumber, version, protocolId)
        return generateUploadUrl(s3Key, "application/pdf")
    }

    /**
     * Generate a presigned URL for uploading a signed protocol PDF.
     */
    fun generateSignedPdfUploadUrl(
        studioId: UUID,
        visitId: UUID,
        visitNumber: String,
        version: Int,
        protocolId: UUID
    ): String {
        val s3Key = buildSignedPdfS3Key(studioId, visitId, visitNumber, version, protocolId)
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
    fun buildTemplateS3Key(
        studioId: UUID,
        templateId: UUID,
        format: pl.detailing.crm.protocol.domain.ProtocolTemplateFormat =
            pl.detailing.crm.protocol.domain.ProtocolTemplateFormat.PDF
    ): String {
        return "$studioId/protocols/templates/$templateId.${format.fileExtension}"
    }

    /**
     * Build S3 key for a filled protocol PDF.
     * Format: {studioId}/protocols/visits/{visitId}/filled/PPP_{visitNumber}_{version}_{protocolId}.pdf
     *
     * protocolId w nazwie z tego samego powodu co przy plikach podpisanych: jedna
     * wizyta ma kilka dokumentów, a wersje liczą się per szablon, więc po samym
     * numerze wizyty pliki wpadałyby na siebie.
     */
    fun buildFilledPdfS3Key(
        studioId: UUID,
        visitId: UUID,
        visitNumber: String,
        version: Int,
        protocolId: UUID
    ): String {
        return "$studioId/protocols/visits/$visitId/filled/PPP_${visitNumber}_${version}_$protocolId.pdf"
    }

    /**
     * Build S3 key for a filled HTML protocol (HTML-format templates).
     */
    fun buildFilledHtmlS3Key(
        studioId: UUID,
        visitId: UUID,
        visitNumber: String,
        version: Int,
        protocolId: UUID
    ): String {
        return "$studioId/protocols/visits/$visitId/filled/PPP_${visitNumber}_${version}_$protocolId.html"
    }

    /**
     * Build S3 key for a signed protocol PDF.
     * Format: {studioId}/protocols/visits/{visitId}/signed/PPP_{visitNumber}_{version}.pdf
     * Example: studio123/protocols/visits/visit456/signed/PPP_VIS-2026-00005_1.pdf
     */
    /**
     * Klucz wypełnionej zgody marketingowej dla konkretnej wizyty.
     *
     * Zgoda ma własną nazwę i własny protocolId w kluczu, bo w jednej wizycie stoi
     * obok protokołu przyjęcia — a ten liczy wersje niezależnie, więc oba dokumenty
     * są „wersją 1" i po samym numerze wizyty wpadłyby na siebie.
     */
    fun buildFilledConsentPdfS3Key(
        studioId: UUID,
        visitId: UUID,
        visitNumber: String,
        protocolId: UUID
    ): String {
        return "$studioId/protocols/visits/$visitId/filled/ZGD_${visitNumber}_$protocolId.pdf"
    }

    fun buildSignedPdfS3Key(
        studioId: UUID,
        visitId: UUID,
        visitNumber: String,
        version: Int,
        protocolId: UUID
    ): String {
        // protocolId w nazwie, bo jedna wizyta ma dziś kilka dokumentów do podpisu
        // (protokół przyjęcia + zgody marketingowe + dokumenty usługowe), a każdy
        // z nich jest wersją 1. Bez tego drugi podpis nadpisywałby w S3 plik
        // pierwszego i oba wiersze wskazywałyby ten sam dokument.
        return "$studioId/protocols/visits/$visitId/signed/PPP_${visitNumber}_${version}_$protocolId.pdf"
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

    /**
     * Upload raw PDF bytes to S3.
     * Used by the tablet signing flow to store the sealed, signed protocol.
     */
    fun uploadBytes(s3Key: String, data: ByteArray, contentType: String = "application/pdf") {
        val putObjectRequest = PutObjectRequest.builder()
            .bucket(bucketName)
            .key(s3Key)
            .contentType(contentType)
            .build()
        s3Client.putObject(putObjectRequest, software.amazon.awssdk.core.sync.RequestBody.fromBytes(data))
    }

    /** True when the object is present; false on a clean 404. Other S3 errors propagate. */
    fun objectExists(s3Key: String): Boolean = try {
        s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(s3Key).build())
        true
    } catch (e: NoSuchKeyException) {
        false
    }

    /**
     * Download the raw bytes of a file stored in S3.
     * Used by the email module to attach protocol PDFs to outgoing messages.
     */
    fun downloadBytes(s3Key: String): ByteArray {
        val getObjectRequest = GetObjectRequest.builder()
            .bucket(bucketName)
            .key(s3Key)
            .build()
        return s3Client.getObject(getObjectRequest).readAllBytes()
    }

    /**
     * Delete a file from S3.
     * Used when cancelling draft visits to clean up generated protocols.
     */
    suspend fun deleteFile(s3Key: String): Unit = withContext(Dispatchers.IO) {
        try {
            val deleteObjectRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build()

            s3Client.deleteObject(deleteObjectRequest)

            logger.info("Successfully deleted protocol file from S3: $s3Key")

        } catch (e: Exception) {
            logger.error("Failed to delete protocol file from S3: $s3Key", e)
            throw e
        }
    }
}
