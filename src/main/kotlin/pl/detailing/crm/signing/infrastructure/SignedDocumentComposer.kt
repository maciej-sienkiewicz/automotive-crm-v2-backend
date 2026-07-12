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

        /**
         * Inner margin (PDF points) between the signature field's border and the stamped
         * strokes, so the signature never touches or overlaps the printed frame.
         */
        private const val SIGNATURE_BOX_PADDING = 3f
    }

    fun compose(
        filledPdfBytes: ByteArray,
        signaturePngBytes: ByteArray,
        request: SignatureRequest,
        auditEvents: List<SignatureAuditEventEntity>,
        visitNumber: String,
        sealInfo: String
    ): ByteArray {
        logger.info(
            "[Composer] requestId={} protocolId={} — composing signed PDF: " +
                "filledPdf={}B signaturePng={}B auditEvents={}",
            request.id, request.protocolId,
            filledPdfBytes.size, signaturePngBytes.size, auditEvents.size
        )

        Loader.loadPDF(filledPdfBytes).use { document ->
            if (document.numberOfPages == 0) {
                throw IllegalArgumentException("Dokument PDF nie zawiera stron")
            }

            logger.debug(
                "[Composer] requestId={} — source PDF loaded: {} page(s)",
                request.id, document.numberOfPages
            )

            val signatureImage = PDImageXObject.createFromByteArray(
                document, signaturePngBytes, "signature"
            )

            logSignatureFieldDiagnostics(document, request, signatureImage, signaturePngBytes.size)

            val placement = findSignaturePlacement(document, request)

            // Shrink the target box by an inner padding so the strokes never touch the
            // field's printed frame, then scale the image down (never up) preserving the
            // stroke aspect ratio, and center it inside the box. A large signature drawn
            // on a large tablet is thus reduced to fit entirely within the frame.
            val availableWidth = (placement.maxWidth - 2 * SIGNATURE_BOX_PADDING).coerceAtLeast(1f)
            val availableHeight = (placement.maxHeight - 2 * SIGNATURE_BOX_PADDING).coerceAtLeast(1f)
            val (width, height) = fitPreservingAspect(
                signatureImage.width.toFloat(), signatureImage.height.toFloat(),
                availableWidth, availableHeight
            )
            val drawX = placement.x + (placement.maxWidth - width) / 2
            val drawY = placement.y + (placement.maxHeight - height) / 2

            logger.info(
                "[Composer] requestId={} — signature stamping: image={}x{}px, box={}x{}pt at ({}, {}) " +
                    "page={}, padding={}pt, scale={}, drawn={}x{}pt at ({}, {}), fallbackUsed={}",
                request.id,
                signatureImage.width, signatureImage.height,
                placement.maxWidth, placement.maxHeight, placement.x, placement.y,
                document.pages.indexOf(placement.page),
                SIGNATURE_BOX_PADDING,
                String.format("%.4f", width / signatureImage.width),
                String.format("%.1f", width), String.format("%.1f", height),
                String.format("%.1f", drawX), String.format("%.1f", drawY),
                placement.fallbackUsed
            )

            PDPageContentStream(
                document, placement.page, PDPageContentStream.AppendMode.APPEND, true, true
            ).use { cs ->
                cs.drawImage(signatureImage, drawX, drawY, width, height)
            }

            // Flatten AcroForm fields so the visual state is frozen before sealing
            document.documentCatalog.acroForm?.flatten()

            val pagesBeforeAudit = document.numberOfPages
            auditTrailPageGenerator.appendAuditPage(document, request, auditEvents, visitNumber, sealInfo)
            val pagesAfterAudit = document.numberOfPages
            logger.info(
                "[Composer] requestId={} — after appendAuditPage: pages {} → {}",
                request.id, pagesBeforeAudit, pagesAfterAudit
            )

            return ByteArrayOutputStream().use { output ->
                document.save(output)
                val composedBytes = output.toByteArray()
                logger.info(
                    "[Composer] requestId={} — composed PDF ready: {}B, {} page(s)",
                    request.id, composedBytes.size, pagesAfterAudit
                )
                composedBytes
            }
        }
    }

    private data class Placement(
        val page: PDPage,
        val x: Float,
        val y: Float,
        val maxWidth: Float,
        val maxHeight: Float,
        val fallbackUsed: Boolean
    )

    /**
     * Prefer the rectangle of the AcroForm field named "signature" (templates reserve it);
     * fall back to a fixed area at the bottom of the last page.
     */
    private fun findSignaturePlacement(
        document: org.apache.pdfbox.pdmodel.PDDocument,
        request: SignatureRequest
    ): Placement {
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
                    maxHeight = rect.height,
                    fallbackUsed = false
                )
            }
        }
        logger.warn(
            "[Composer] requestId={} — no usable '{}' field (acroFormPresent={}, fieldFound={}, " +
                "widgetFound={}, see diagnostics above) — stamping at FALLBACK position on last page: " +
                "x={}, y={}, box={}x{}pt",
            request.id, SIGNATURE_FIELD_NAME,
            acroForm != null, field != null, widget != null,
            FALLBACK_X, FALLBACK_Y, FALLBACK_MAX_WIDTH, FALLBACK_MAX_HEIGHT
        )
        return Placement(
            page = document.getPage(document.numberOfPages - 1),
            x = FALLBACK_X,
            y = FALLBACK_Y,
            maxWidth = FALLBACK_MAX_WIDTH,
            maxHeight = FALLBACK_MAX_HEIGHT,
            fallbackUsed = true
        )
    }

    /**
     * Dump everything we know about the "signature" AcroForm field and the incoming
     * signature image at INFO level. Deliberately verbose: when a signature lands in the
     * wrong place, gets clipped or scaled oddly in production, this log is the forensic
     * record for the next iteration (templates differ between environments and are only
     * reachable through the production S3 bucket).
     */
    private fun logSignatureFieldDiagnostics(
        document: org.apache.pdfbox.pdmodel.PDDocument,
        request: SignatureRequest,
        signatureImage: PDImageXObject,
        signaturePngSize: Int
    ) {
        try {
            logger.info(
                "[Composer] requestId={} — signature image: {}x{}px ({}B), aspectRatio={}",
                request.id, signatureImage.width, signatureImage.height, signaturePngSize,
                String.format("%.3f", signatureImage.width.toFloat() / signatureImage.height)
            )

            val acroForm = document.documentCatalog.acroForm
            if (acroForm == null) {
                logger.warn(
                    "[Composer] requestId={} — signature field diagnostics: document has NO AcroForm " +
                        "(the '{}' field was flattened away or the template never had it)",
                    request.id, SIGNATURE_FIELD_NAME
                )
                return
            }

            val allFieldNames = acroForm.fieldTree.map { it.fullyQualifiedName }
            logger.info(
                "[Composer] requestId={} — AcroForm: needAppearances={}, fields({})={}",
                request.id, acroForm.needAppearances, allFieldNames.size, allFieldNames
            )

            val field = acroForm.getField(SIGNATURE_FIELD_NAME)
            if (field == null) {
                logger.warn(
                    "[Composer] requestId={} — field '{}' NOT FOUND in AcroForm",
                    request.id, SIGNATURE_FIELD_NAME
                )
                return
            }

            logger.info(
                "[Composer] requestId={} — field '{}': type={}, fieldType={}, partialName={}, " +
                    "readOnly={}, required={}, widgets={}",
                request.id, SIGNATURE_FIELD_NAME,
                field.javaClass.simpleName, field.fieldType, field.partialName,
                field.isReadOnly, field.isRequired,
                (field as? PDTerminalField)?.widgets?.size ?: 0
            )

            (field as? PDTerminalField)?.widgets?.forEachIndexed { index, widget ->
                val rect = widget.rectangle
                val pageViaP = widget.page
                val pageResolved = pageViaP ?: pageOfWidget(document, widget)
                val pageIndex = pageResolved?.let { document.pages.indexOf(it) } ?: -1
                val inAnnots = pageResolved
                    ?.annotations?.any { it.cosObject == widget.cosObject } ?: false
                logger.info(
                    "[Composer] requestId={} — field '{}' widget[{}]: rect=[llx={}, lly={}, urx={}, ury={}] " +
                        "({}x{}pt), pageRef(/P)={}, resolvedPageIndex={}, inPageAnnots={}, " +
                        "hidden={}, invisible={}, noView={}, printed={}, hasAppearance(/AP)={}, " +
                        "pageMediaBox={}x{}pt, pageRotation={}",
                    request.id, SIGNATURE_FIELD_NAME, index,
                    rect?.lowerLeftX, rect?.lowerLeftY, rect?.upperRightX, rect?.upperRightY,
                    rect?.width, rect?.height,
                    pageViaP != null, pageIndex, inAnnots,
                    widget.isHidden, widget.isInvisible, widget.isNoView, widget.isPrinted,
                    widget.appearance != null,
                    pageResolved?.mediaBox?.width, pageResolved?.mediaBox?.height,
                    pageResolved?.rotation
                )
            }
        } catch (e: Exception) {
            // Diagnostics must never break signing
            logger.warn(
                "[Composer] requestId={} — failed to collect signature field diagnostics: {}",
                request.id, e.message
            )
        }
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
