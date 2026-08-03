package pl.detailing.crm.signing.infrastructure

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
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
 *       or the bottom of the last page as fallback)
 *     + optional employee signature (stamped into the "company_signature" field area;
 *       skipped gracefully when the template has no such field or the employee has
 *       no configured signature)
 *     + flattening of all form fields (visual immutability)
 *     + appended audit page (Karta Podpisu)
 *
 * The output of this composer is the input of [QualifiedSealService] — the seal and
 * qualified timestamp cover the document, the signature images AND the audit page,
 * closing the cryptographic loop. No intermediate artifact is persisted anywhere.
 */
@Service
class SignedDocumentComposer(
    private val auditTrailPageGenerator: AuditTrailPageGenerator
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val SIGNATURE_FIELD_NAME = "signature"
        private const val COMPANY_SIGNATURE_FIELD_NAME = "company_signature"
        private const val FALLBACK_X = 50f
        private const val FALLBACK_Y = 50f
        private const val FALLBACK_MAX_WIDTH = 200f
        private const val FALLBACK_MAX_HEIGHT = 80f

        /**
         * Inner margin (PDF points) between a signature field's border and the stamped
         * strokes, so the signature never touches or overlaps the printed frame.
         */
        private const val SIGNATURE_BOX_PADDING = 3f
    }

    fun compose(
        filledPdfBytes: ByteArray,
        signaturePngBytes: ByteArray,
        companySignaturePngBytes: ByteArray?,
        request: SignatureRequest,
        auditEvents: List<SignatureAuditEventEntity>,
        visitNumber: String,
        sealInfo: String
    ): ByteArray {
        logger.info(
            "[Composer] requestId={} protocolId={} — composing signed PDF: " +
                "filledPdf={}B signaturePng={}B companySignature={} auditEvents={}",
            request.id, request.protocolId,
            filledPdfBytes.size, signaturePngBytes.size,
            if (companySignaturePngBytes != null) "${companySignaturePngBytes.size}B" else "none",
            auditEvents.size
        )

        Loader.loadPDF(filledPdfBytes).use { document ->
            if (document.numberOfPages == 0) {
                throw IllegalArgumentException("Dokument PDF nie zawiera stron")
            }

            logger.debug(
                "[Composer] requestId={} — source PDF loaded: {} page(s)",
                request.id, document.numberOfPages
            )

            // ── Customer signature (required) ────────────────────────────────────
            val customerImage = PDImageXObject.createFromByteArray(document, signaturePngBytes, "signature")
            logSignatureFieldDiagnostics(document, request, SIGNATURE_FIELD_NAME, customerImage, signaturePngBytes.size)

            val customerPlacement = findPlacement(document, SIGNATURE_FIELD_NAME, request, useFallback = true)!!
            stampImage(document, customerImage, customerPlacement, request.id.toString(), SIGNATURE_FIELD_NAME)

            // ── Employee / company signature (optional) ──────────────────────────
            if (companySignaturePngBytes != null) {
                val companyImage = PDImageXObject.createFromByteArray(document, companySignaturePngBytes, "company_signature")
                logSignatureFieldDiagnostics(document, request, COMPANY_SIGNATURE_FIELD_NAME, companyImage, companySignaturePngBytes.size)

                val companyPlacement = findPlacement(document, COMPANY_SIGNATURE_FIELD_NAME, request, useFallback = false)
                if (companyPlacement != null) {
                    stampImage(document, companyImage, companyPlacement, request.id.toString(), COMPANY_SIGNATURE_FIELD_NAME)
                } else {
                    logger.info(
                        "[Composer] requestId={} — no usable '{}' field in template; " +
                            "company signature not stamped (template does not support it yet)",
                        request.id, COMPANY_SIGNATURE_FIELD_NAME
                    )
                }
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
     * Locate the widget rectangle of [fieldName] in the document's AcroForm.
     *
     * When [useFallback] is true and the field is absent/unusable, returns a hardcoded
     * position at the bottom of the last page (used for the mandatory customer signature).
     * When [useFallback] is false, returns null instead of a fallback position (used for
     * the optional company signature — we must not stamp it in a random location).
     */
    private fun findPlacement(
        document: PDDocument,
        fieldName: String,
        request: SignatureRequest,
        useFallback: Boolean
    ): Placement? {
        val acroForm = document.documentCatalog.acroForm
        val field = acroForm?.getField(fieldName) as? PDTerminalField
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

        if (!useFallback) {
            logger.warn(
                "[Composer] requestId={} — no usable '{}' field " +
                    "(acroFormPresent={}, fieldFound={}, widgetFound={})",
                request.id, fieldName, acroForm != null, field != null, widget != null
            )
            return null
        }

        logger.warn(
            "[Composer] requestId={} — no usable '{}' field (acroFormPresent={}, fieldFound={}, " +
                "widgetFound={}) — stamping at FALLBACK position on last page: " +
                "x={}, y={}, box={}x{}pt",
            request.id, fieldName,
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

    private fun stampImage(
        document: PDDocument,
        image: PDImageXObject,
        placement: Placement,
        requestId: String,
        fieldName: String
    ) {
        val availableWidth = (placement.maxWidth - 2 * SIGNATURE_BOX_PADDING).coerceAtLeast(1f)
        val availableHeight = (placement.maxHeight - 2 * SIGNATURE_BOX_PADDING).coerceAtLeast(1f)
        val (width, height) = fitPreservingAspect(
            image.width.toFloat(), image.height.toFloat(),
            availableWidth, availableHeight
        )
        val drawX = placement.x + (placement.maxWidth - width) / 2
        val drawY = placement.y + (placement.maxHeight - height) / 2

        logger.info(
            "[Composer] requestId={} field='{}' — stamping: image={}x{}px box={}x{}pt at ({},{}) " +
                "page={} scale={} drawn={}x{}pt at ({},{}) fallback={}",
            requestId, fieldName,
            image.width, image.height,
            placement.maxWidth, placement.maxHeight, placement.x, placement.y,
            document.pages.indexOf(placement.page),
            String.format("%.4f", width / image.width),
            String.format("%.1f", width), String.format("%.1f", height),
            String.format("%.1f", drawX), String.format("%.1f", drawY),
            placement.fallbackUsed
        )

        PDPageContentStream(
            document, placement.page, PDPageContentStream.AppendMode.APPEND, true, true
        ).use { cs ->
            cs.drawImage(image, drawX, drawY, width, height)
        }
    }

    /**
     * Dump everything we know about an AcroForm field and the incoming signature image
     * at INFO level. Deliberately verbose: when a signature lands in the wrong place or
     * gets clipped oddly in production, this log is the forensic record.
     */
    private fun logSignatureFieldDiagnostics(
        document: PDDocument,
        request: SignatureRequest,
        fieldName: String,
        signatureImage: PDImageXObject,
        signaturePngSize: Int
    ) {
        try {
            logger.info(
                "[Composer] requestId={} field='{}' — image: {}x{}px ({}B) aspectRatio={}",
                request.id, fieldName,
                signatureImage.width, signatureImage.height, signaturePngSize,
                String.format("%.3f", signatureImage.width.toFloat() / signatureImage.height)
            )

            val acroForm = document.documentCatalog.acroForm
            if (acroForm == null) {
                logger.warn(
                    "[Composer] requestId={} field='{}' — document has NO AcroForm",
                    request.id, fieldName
                )
                return
            }

            val allFieldNames = acroForm.fieldTree.map { it.fullyQualifiedName }
            logger.info(
                "[Composer] requestId={} — AcroForm: needAppearances={}, fields({})={}",
                request.id, acroForm.needAppearances, allFieldNames.size, allFieldNames
            )

            val field = acroForm.getField(fieldName)
            if (field == null) {
                logger.warn(
                    "[Composer] requestId={} — field '{}' NOT FOUND in AcroForm",
                    request.id, fieldName
                )
                return
            }

            logger.info(
                "[Composer] requestId={} — field '{}': type={} partialName={} readOnly={} required={} widgets={}",
                request.id, fieldName,
                field.javaClass.simpleName, field.partialName,
                field.isReadOnly, field.isRequired,
                (field as? PDTerminalField)?.widgets?.size ?: 0
            )

            (field as? PDTerminalField)?.widgets?.forEachIndexed { index, widget ->
                val rect = widget.rectangle
                val pageViaP = widget.page
                val pageResolved = pageViaP ?: pageOfWidget(document, widget)
                val pageIndex = pageResolved?.let { document.pages.indexOf(it) } ?: -1
                val inAnnots = pageResolved?.annotations?.any { it.cosObject == widget.cosObject } ?: false
                logger.info(
                    "[Composer] requestId={} field='{}' widget[{}]: rect=[{},{},{},{}] ({}x{}pt) " +
                        "pageRef=/P:{} resolvedPage={} inAnnots={} hidden={} invisible={} noView={} " +
                        "printed={} hasAP={} mediaBox={}x{}pt rotation={}",
                    request.id, fieldName, index,
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
            logger.warn(
                "[Composer] requestId={} field='{}' — failed to collect diagnostics: {}",
                request.id, fieldName, e.message
            )
        }
    }

    private fun pageOfWidget(document: PDDocument, widget: PDAnnotationWidget): PDPage? {
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
