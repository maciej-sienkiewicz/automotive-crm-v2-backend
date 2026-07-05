package pl.detailing.crm.signing.infrastructure

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.signing.domain.SignatureRequest
import java.io.ByteArrayOutputStream

/**
 * Composes the final signed PDF entirely in RAM:
 *
 *   filled protocol PDF
 *     + transparent signature strokes (stamped into the "signature" field area
 *       or the bottom of the last page)
 *     + flattening of all form fields (visual immutability)
 *     + appended audit page (Karta Podpisu)
 *
 * The output of this composer is the input of [QualifiedSealService] — the seal and
 * qualified timestamp cover the document, the signature image AND the audit page,
 * closing the cryptographic loop. No intermediate artifact is persisted anywhere.
 */
@Service
class SignedDocumentComposer(
    private val auditTrailPageGenerator: AuditTrailPageGenerator
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val SIGNATURE_FIELD_NAME = "signature"
        private const val FALLBACK_X = 50f
        private const val FALLBACK_Y = 50f
        private const val FALLBACK_MAX_WIDTH = 200f
        private const val FALLBACK_MAX_HEIGHT = 80f
    }

    fun compose(
        filledPdfBytes: ByteArray,
        signaturePngBytes: ByteArray,
        request: SignatureRequest,
        auditEvents: List<SignatureAuditEventEntity>,
        visitNumber: String,
        sealInfo: String
    ): ByteArray {
        Loader.loadPDF(filledPdfBytes).use { document ->
            if (document.numberOfPages == 0) {
                throw IllegalArgumentException("Dokument PDF nie zawiera stron")
            }

            val signatureImage = PDImageXObject.createFromByteArray(
                document, signaturePngBytes, "signature"
            )

            val placement = findSignaturePlacement(document)
            val (width, height) = fitPreservingAspect(
                signatureImage.width.toFloat(), signatureImage.height.toFloat(),
                placement.maxWidth, placement.maxHeight
            )

            PDPageContentStream(
                document, placement.page, PDPageContentStream.AppendMode.APPEND, true, true
            ).use { cs ->
                cs.drawImage(signatureImage, placement.x, placement.y, width, height)
            }

            // Flatten AcroForm fields so the visual state is frozen before sealing
            document.documentCatalog.acroForm?.flatten()

            auditTrailPageGenerator.appendAuditPage(document, request, auditEvents, visitNumber, sealInfo)

            return ByteArrayOutputStream().use { output ->
                document.save(output)
                output.toByteArray()
            }
        }
    }

    private data class Placement(
        val page: PDPage,
        val x: Float,
        val y: Float,
        val maxWidth: Float,
        val maxHeight: Float
    )

    /**
     * Prefer the rectangle of the AcroForm field named "signature" (templates reserve it);
     * fall back to a fixed area at the bottom of the last page.
     */
    private fun findSignaturePlacement(document: org.apache.pdfbox.pdmodel.PDDocument): Placement {
        val acroForm = document.documentCatalog.acroForm
        val field = acroForm?.getField(SIGNATURE_FIELD_NAME) as? PDTerminalField
        val widget = field?.widgets?.firstOrNull()
        if (widget != null) {
            val rect = widget.rectangle
            val page = widget.page ?: pageOfWidget(document, widget)
            if (page != null && rect != null && rect.width > 1f && rect.height > 1f) {
                return Placement(
                    page = page,
                    x = rect.lowerLeftX,
                    y = rect.lowerLeftY,
                    maxWidth = rect.width,
                    maxHeight = rect.height
                )
            }
        }
        logger.debug("No usable '$SIGNATURE_FIELD_NAME' field — stamping at fallback position on last page")
        return Placement(
            page = document.getPage(document.numberOfPages - 1),
            x = FALLBACK_X,
            y = FALLBACK_Y,
            maxWidth = FALLBACK_MAX_WIDTH,
            maxHeight = FALLBACK_MAX_HEIGHT
        )
    }

    private fun pageOfWidget(
        document: org.apache.pdfbox.pdmodel.PDDocument,
        widget: org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
    ): PDPage? {
        for (page in document.pages) {
            if (page.annotations.any { it.cosObject == widget.cosObject }) return page
        }
        return null
    }

    /** Scale the signature to fit the target box without distorting the strokes. */
    private fun fitPreservingAspect(
        imageWidth: Float,
        imageHeight: Float,
        maxWidth: Float,
        maxHeight: Float
    ): Pair<Float, Float> {
        val scale = minOf(maxWidth / imageWidth, maxHeight / imageHeight, 1f)
        return imageWidth * scale to imageHeight * scale
    }
}
