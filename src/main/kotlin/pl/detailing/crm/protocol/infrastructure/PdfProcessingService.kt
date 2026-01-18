package pl.detailing.crm.protocol.infrastructure

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Service for processing PDFs: form filling, signature application, and flattening.
 *
 * Uses Apache PDFBox for PDF manipulation.
 */
@Service
class PdfProcessingService(
    private val s3Client: S3Client,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String
) {

    /**
     * Fill a PDF form with data and upload to S3.
     *
     * @param templateS3Key S3 key of the template PDF
     * @param fieldMappings Map of PDF field names to values
     * @param outputS3Key S3 key where the filled PDF will be stored
     * @return The output S3 key
     */
    fun fillPdfForm(
        templateS3Key: String,
        fieldMappings: Map<String, String>,
        outputS3Key: String
    ): String {
        // Download template from S3
        val templateBytes = downloadFromS3(templateS3Key)

        // Fill the form
        val filledPdfBytes = fillForm(templateBytes, fieldMappings)

        // Upload filled PDF to S3
        uploadToS3(outputS3Key, filledPdfBytes, "application/pdf")

        return outputS3Key
    }

    /**
     * Add a signature image to a PDF and flatten it.
     *
     * @param pdfS3Key S3 key of the PDF to sign
     * @param signatureImageS3Key S3 key of the signature image
     * @param outputS3Key S3 key where the signed PDF will be stored
     * @param signatureX X coordinate for signature placement
     * @param signatureY Y coordinate for signature placement
     * @param signatureWidth Width of the signature image
     * @param signatureHeight Height of the signature image
     * @return The output S3 key
     */
    fun signAndFlattenPdf(
        pdfS3Key: String,
        signatureImageS3Key: String,
        outputS3Key: String,
        signatureX: Float = 50f,
        signatureY: Float = 50f,
        signatureWidth: Float = 200f,
        signatureHeight: Float = 80f
    ): String {
        // Download PDF and signature image from S3
        val pdfBytes = downloadFromS3(pdfS3Key)
        val signatureBytes = downloadFromS3(signatureImageS3Key)

        // Add signature and flatten
        val signedPdfBytes = addSignatureAndFlatten(
            pdfBytes,
            signatureBytes,
            signatureX,
            signatureY,
            signatureWidth,
            signatureHeight
        )

        // Upload signed PDF to S3
        uploadToS3(outputS3Key, signedPdfBytes, "application/pdf")

        return outputS3Key
    }

    /**
     * Fill PDF form fields with provided data.
     */
    private fun fillForm(pdfBytes: ByteArray, fieldMappings: Map<String, String>): ByteArray {
        return ByteArrayInputStream(pdfBytes).use { inputStream ->
            Loader.loadPDF(inputStream.readBytes()).use { document ->
                val acroForm: PDAcroForm? = document.documentCatalog.acroForm

                if (acroForm == null) {
                    throw IllegalArgumentException("PDF does not contain an AcroForm")
                }

                // Set default appearance for UTF-8 support
                acroForm.defaultAppearance = "/Helv 0 Tf 0 g"

                // Fill each field
                fieldMappings.forEach { (fieldName, value) ->
                    try {
                        val field = acroForm.getField(fieldName)
                        if (field != null) {
                            field.setValue(value)
                        }
                    } catch (e: Exception) {
                        // Log warning but continue processing other fields
                        println("Warning: Could not set value for field '$fieldName': ${e.message}")
                    }
                }

                // Save to byte array
                ByteArrayOutputStream().use { outputStream ->
                    document.save(outputStream)
                    outputStream.toByteArray()
                }
            }
        }
    }

    /**
     * Add a signature image to the last page of a PDF and flatten it.
     *
     * Flattening makes the PDF immutable by merging form fields into the content stream.
     */
    private fun addSignatureAndFlatten(
        pdfBytes: ByteArray,
        signatureImageBytes: ByteArray,
        x: Float,
        y: Float,
        width: Float,
        height: Float
    ): ByteArray {
        return ByteArrayInputStream(pdfBytes).use { inputStream ->
            Loader.loadPDF(inputStream.readBytes()).use { document ->
                // Get the last page
                val pageCount = document.numberOfPages
                if (pageCount == 0) {
                    throw IllegalArgumentException("PDF has no pages")
                }
                val lastPage: PDPage = document.getPage(pageCount - 1)

                // Create image from signature bytes
                val signatureImage = PDImageXObject.createFromByteArray(
                    document,
                    signatureImageBytes,
                    "signature"
                )

                // Add signature image to the last page
                PDPageContentStream(document, lastPage, PDPageContentStream.AppendMode.APPEND, true).use { contentStream ->
                    contentStream.drawImage(signatureImage, x, y, width, height)
                }

                // Flatten the form (merge fields into content)
                document.documentCatalog.acroForm?.let { acroForm ->
                    acroForm.flatten()
                }

                // Save to byte array
                ByteArrayOutputStream().use { outputStream ->
                    document.save(outputStream)
                    outputStream.toByteArray()
                }
            }
        }
    }

    /**
     * Download a file from S3.
     */
    private fun downloadFromS3(s3Key: String): ByteArray {
        val getObjectRequest = GetObjectRequest.builder()
            .bucket(bucketName)
            .key(s3Key)
            .build()

        return s3Client.getObject(getObjectRequest).use { response ->
            response.readAllBytes()
        }
    }

    /**
     * Upload a file to S3.
     */
    private fun uploadToS3(s3Key: String, data: ByteArray, contentType: String) {
        val putObjectRequest = PutObjectRequest.builder()
            .bucket(bucketName)
            .key(s3Key)
            .contentType(contentType)
            .build()

        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(data))
    }
}
