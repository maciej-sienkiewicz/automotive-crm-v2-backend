package pl.detailing.crm.visit.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
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
     * Upload a damage map PDF to S3.
     *
     * @param studioId The studio ID
     * @param visitId The visit ID
     * @param pdfBytes The PDF bytes
     * @return The S3 key where the file was stored
     */
    suspend fun uploadDamageMap(
        studioId: UUID,
        visitId: UUID,
        pdfBytes: ByteArray,
        /**
         * null = kanoniczny klucz wizyty (nadpisanie poprzedniego pliku).
         * Podany = osobny plik obok dotychczasowego, np. `damage-map-r2.pdf`.
         * Aktualizacja mapy w trakcie wizyty korzysta z obu wariantów, bo to jest
         * dokładnie różnica między „popraw dokument" i „wystaw nowy".
         */
        revisionSuffix: String? = null
    ): String = withContext(Dispatchers.IO) {
        val s3Key = buildDamageMapS3Key(studioId, visitId, revisionSuffix)

        try {
            val putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .contentType("application/pdf")
                .contentLength(pdfBytes.size.toLong())
                .metadata(mapOf(
                    "studio-id" to studioId.toString(),
                    "visit-id" to visitId.toString()
                ))
                .build()

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(pdfBytes))

            logger.info("Successfully uploaded damage map PDF to S3: $s3Key (${pdfBytes.size} bytes)")

            return@withContext s3Key

        } catch (e: Exception) {
            logger.error("Failed to upload damage map PDF to S3: $s3Key", e)
            throw IllegalStateException("Failed to upload damage map to S3: ${e.message}", e)
        }
    }

    /**
     * Nadpisuje mapę uszkodzeń pod KONKRETNYM, już istniejącym kluczem.
     *
     * Do opcji „zaktualizuj istniejący plik": aktualną mapą wizyty niekoniecznie
     * jest kanoniczne `damage-map.pdf` (mogła nią zostać wcześniejsza rewizja),
     * a podmiana ma dotyczyć tego pliku, na który wizyta faktycznie wskazuje.
     */
    suspend fun uploadDamageMapToKey(
        s3Key: String,
        pdfBytes: ByteArray
    ): Unit = withContext(Dispatchers.IO) {
        try {
            s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType("application/pdf")
                    .contentLength(pdfBytes.size.toLong())
                    .build(),
                RequestBody.fromBytes(pdfBytes)
            )
            logger.info("Overwrote damage map PDF in S3: $s3Key (${pdfBytes.size} bytes)")
        } catch (e: Exception) {
            logger.error("Failed to overwrite damage map PDF in S3: $s3Key", e)
            throw IllegalStateException("Failed to overwrite damage map in S3: ${e.message}", e)
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
     * Build S3 key for a damage map PDF.
     *
     * Pattern: {studioId}/visits/{visitId}/damage-map.pdf
     *          {studioId}/visits/{visitId}/damage-map-{revisionSuffix}.pdf
     */
    fun buildDamageMapS3Key(studioId: UUID, visitId: UUID, revisionSuffix: String? = null): String {
        val suffix = revisionSuffix
            ?.trim()
            ?.lowercase()
            // Klucz S3 składa się tu ze stringów, więc sanitacja jest obowiązkowa:
            // suffix bierze się z numeru rewizji, ale nie ma powodu, żeby ufać temu
            // w miejscu, gdzie „../" zmieniłoby ścieżkę pliku.
            ?.replace(Regex("[^a-z0-9-]"), "")
            ?.takeIf { it.isNotEmpty() }
        return if (suffix == null) {
            "$studioId/visits/$visitId/damage-map.pdf"
        } else {
            "$studioId/visits/$visitId/damage-map-$suffix.pdf"
        }
    }

    fun downloadBytes(s3Key: String): ByteArray {
        val getObjectRequest = GetObjectRequest.builder()
            .bucket(bucketName)
            .key(s3Key)
            .build()
        return s3Client.getObject(getObjectRequest).readAllBytes()
    }

    /**
     * Delete a damage map file from S3.
     * Used when cancelling draft visits to clean up generated damage maps.
     *
     * @param s3Key The S3 key of the damage map to delete
     */
    suspend fun deleteFile(s3Key: String): Unit = withContext(Dispatchers.IO) {
        try {
            val deleteObjectRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build()

            s3Client.deleteObject(deleteObjectRequest)

            logger.info("Successfully deleted damage map from S3: $s3Key")

        } catch (e: Exception) {
            logger.error("Failed to delete damage map from S3: $s3Key", e)
            throw e
        }
    }
}
