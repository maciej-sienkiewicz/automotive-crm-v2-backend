package pl.detailing.crm.protocol.infrastructure

import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox
import org.slf4j.LoggerFactory
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
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MAX_FIELD_FONT_SIZE = 7f

        /**
         * The AcroForm field reserved in protocol templates for the customer's signature.
         * It must survive form filling un-flattened: SignedDocumentComposer reads its widget
         * rectangle to know where (and how large) the tablet signature image may be stamped.
         */
        const val SIGNATURE_FIELD_NAME = "signature"
    }

    /**
     * Flatten all AcroForm fields in a PDF and return the resulting bytes.
     *
     * Merges interactive form widget appearances into the static page content stream,
     * producing a read-only "snapshot" PDF that renders identically in every viewer.
     * Used when attaching protocols to outgoing emails (the attachment must be static).
     */
    fun flattenPdfBytes(pdfBytes: ByteArray): ByteArray {
        return ByteArrayInputStream(pdfBytes).use { inputStream ->
            Loader.loadPDF(inputStream.readBytes()).use { document ->
                document.documentCatalog.acroForm?.let { flattenPreservingContent(document, it) }
                ByteArrayOutputStream().use { outputStream ->
                    document.save(outputStream)
                    outputStream.toByteArray()
                }
            }
        }
    }

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
        val downloadStart = System.currentTimeMillis()
        val templateBytes = downloadFromS3(templateS3Key)
        logger.info("[PERF]     - S3 download template: ${System.currentTimeMillis() - downloadStart}ms (${templateBytes.size} bytes)")

        // Fill the form
        val fillStart = System.currentTimeMillis()
        val filledPdfBytes = fillForm(templateBytes, fieldMappings)
        logger.info("[PERF]     - PDF form filling (PDFBox): ${System.currentTimeMillis() - fillStart}ms (${fieldMappings.size} fields)")

        // Upload filled PDF to S3
        val uploadStart = System.currentTimeMillis()
        uploadToS3(outputS3Key, filledPdfBytes, "application/pdf")
        logger.info("[PERF]     - S3 upload filled PDF: ${System.currentTimeMillis() - uploadStart}ms (${filledPdfBytes.size} bytes)")

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
     * Makes all fields read-only (flattened) EXCEPT the signature field.
     */
    private fun fillForm(pdfBytes: ByteArray, fieldMappings: Map<String, String>): ByteArray {
        return ByteArrayInputStream(pdfBytes).use { inputStream ->
            Loader.loadPDF(inputStream.readBytes()).use { document ->
                // Pass null fixup to skip AcroFormDefaultFixup, which would otherwise trigger
                // PDFBox's FileSystemFontProvider to scan all system fonts (hundreds of files,
                // several seconds of delay). We set up our own font immediately after.
                val acroForm: PDAcroForm? = document.documentCatalog.getAcroForm(null)

                if (acroForm == null) {
                    throw IllegalArgumentException("PDF does not contain an AcroForm")
                }

                // Set up Unicode font to support Polish characters
                val fontEmbedded = setupUnicodeFontForForm(document, acroForm)

                // We embed our own font and PDFBox generates the field appearances itself
                // in setValue(), so the template's NeedAppearances flag (viewer-side
                // appearance generation) is obsolete. Clearing it stops viewers from
                // regenerating appearances with non-embedded fonts and silences the
                // PDAcroForm flatten() warnings.
                if (fontEmbedded) {
                    acroForm.needAppearances = false
                }

                // Fill each field
                var filledCount = 0
                var missedCount = 0
                fieldMappings.forEach { (fieldName, value) ->
                    try {
                        val field = acroForm.getField(fieldName)
                        if (field != null) {
                            if (field is org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox) {
                                when (value.uppercase()) {
                                    "YES", "ON", "TRUE", "1" -> {
                                        val onValue = field.onValues.firstOrNull() ?: "Yes"
                                        field.setValue(onValue)
                                    }
                                    else -> field.setValue("Off")
                                }
                            } else {
                                field.setValue(value)
                            }
                            filledCount++
                        } else {
                            logger.warn("PDF field not found in AcroForm: '$fieldName' (value='${value.take(40)}') — skipping")
                            missedCount++
                        }
                    } catch (e: Exception) {
                        logger.warn("Could not set value for field '$fieldName': ${e.message}")
                    }
                }
                logger.info("PDF form fill: $filledCount/${fieldMappings.size} fields set, $missedCount not found in AcroForm")

                // Flatten all fields into static page content so the PDF renders
                // correctly in every viewer (including pdf.js canvas rendering on
                // the signing tablet) — EXCEPT the signature field. Its widget rectangle
                // must survive: SignedDocumentComposer uses it to position and scale the
                // signature image stamped after tablet signing. Flattening it would erase
                // the field and force the composer onto its blind fallback position.
                flattenPreservingContent(document, acroForm, keepFieldNames = setOf(SIGNATURE_FIELD_NAME))

                // Save to byte array
                ByteArrayOutputStream().use { outputStream ->
                    document.save(outputStream)
                    outputStream.toByteArray()
                }
            }
        }
    }

    /**
     * Load and register a Unicode-capable font (supporting Polish characters) as the default
     * appearance font for the AcroForm. Classpath-bundled fonts are tried first to guarantee
     * consistent rendering across environments (the classpath version is always identical,
     * whereas system fonts vary between dev and production). System fonts serve as fallback
     * only if the JAR-bundled ones cannot be loaded.
     *
     * @return true when a Unicode font was embedded (PDFBox can generate field appearances
     *         itself), false when falling back to Helvetica + needAppearances=true.
     */
    private fun setupUnicodeFontForForm(document: PDDocument, acroForm: PDAcroForm): Boolean {
        val classpathFonts = listOf(
            "/fonts/LiberationSans-Regular.ttf",
            "/fonts/DejaVuSans.ttf"
        )
        logger.info("PDF font setup: trying classpath fonts first: $classpathFonts")
        for (classpathFont in classpathFonts) {
            val stream = javaClass.getResourceAsStream(classpathFont)
            logger.info("PDF font setup: classpath '$classpathFont' — found=${stream != null}")
            stream?.use {
                try {
                    val font = PDType0Font.load(document, it, false)
                    applyFontToAcroForm(document, acroForm, font)
                    logger.info("PDF font setup: SUCCESS — loaded classpath font '$classpathFont'")
                    return true
                } catch (e: Exception) {
                    logger.warn("PDF font setup: failed to load classpath font '$classpathFont': ${e.message}")
                }
            }
        }

        val systemFontPaths = listOf(
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
            "/usr/share/fonts/liberation/LiberationSans-Regular.ttf",
            "/usr/share/fonts/truetype/DejaVu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/TTF/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/freefont/FreeSans.ttf",
            "/usr/share/fonts/google-crosextra-carlito/Carlito-Regular.ttf"
        )
        logger.info("PDF font setup: classpath fonts unavailable, scanning ${systemFontPaths.size} system paths...")
        systemFontPaths.forEach { path ->
            logger.info("PDF font setup: checking $path — exists=${java.io.File(path).exists()}")
        }
        for (path in systemFontPaths) {
            val file = java.io.File(path)
            if (file.exists()) {
                try {
                    val font = PDType0Font.load(document, java.io.FileInputStream(file), false)
                    applyFontToAcroForm(document, acroForm, font)
                    logger.info("PDF font setup: SUCCESS — loaded system font '$path'")
                    return true
                } catch (e: Exception) {
                    logger.warn("PDF font setup: failed to load system font '$path': ${e.message}")
                }
            }
        }

        logger.warn("PDF font setup: FALLBACK — no Unicode font found. Using Helvetica + needAppearances=true. Polish characters WILL be garbled in flattened PDFs.")
        acroForm.needAppearances = true
        acroForm.defaultAppearance = "/Helv 0 Tf 0 g"
        return false
    }

    /**
     * Flatten [acroForm] without losing field values.
     *
     * PDFBox's flatten() only draws widget annotations that are listed in a page's /Annots
     * array — and then removes ALL fields. Templates produced by some editors leave the field
     * widgets out of /Annots (typically together with a missing /P page reference, which
     * PDFBox logs as "widget with a missing page reference"). Flattening such a template
     * silently drops every value and produces a visually blank PDF — exactly what happened
     * in production with the check-in vehicle receipt protocol. [attachOrphanWidgetsToPages]
     * repairs the template before flattening.
     *
     * Additionally, when the document still declares NeedAppearances=true the appearances are
     * refreshed first so flatten() never merges stale or missing appearance streams.
     *
     * Fields listed in [keepFieldNames] are left interactive (not flattened) — used to keep
     * the signature field's rectangle available for the signing step.
     */
    private fun flattenPreservingContent(
        document: PDDocument,
        acroForm: PDAcroForm,
        keepFieldNames: Set<String> = emptySet()
    ) {
        attachOrphanWidgetsToPages(document, acroForm)
        if (acroForm.needAppearances) {
            try {
                acroForm.refreshAppearances()
                acroForm.needAppearances = false
            } catch (e: Exception) {
                logger.warn("PDF flatten: could not refresh appearances: ${e.message}")
            }
        }
        if (keepFieldNames.isEmpty()) {
            acroForm.flatten()
        } else {
            val fieldsToFlatten = acroForm.fieldTree.filter { it.fullyQualifiedName !in keepFieldNames }
            logger.info(
                "PDF flatten: flattening ${fieldsToFlatten.size} field(s), " +
                    "keeping interactive: ${keepFieldNames.filter { acroForm.getField(it) != null }}"
            )
            acroForm.flatten(fieldsToFlatten, false)
        }
    }

    /**
     * Ensure every field widget annotation is referenced by a page's /Annots array.
     *
     * A widget that no page references cannot be rendered by any viewer and is silently
     * skipped (then deleted) by flatten(). Orphaned widgets are attached to the page they
     * point to via /P, or to the first page when the reference is missing (protocol
     * templates are single-page).
     */
    private fun attachOrphanWidgetsToPages(document: PDDocument, acroForm: PDAcroForm) {
        if (document.numberOfPages == 0) return

        val annotatedWidgets = document.pages
            .flatMap { page -> page.annotations.map { it.cosObject } }
            .toSet()

        var repaired = 0
        for (field in acroForm.fieldTree) {
            if (field !is org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField) continue
            for (widget in field.widgets) {
                if (widget.cosObject in annotatedWidgets) continue

                val page = widget.page ?: document.getPage(0).also { widget.page = it }
                page.annotations.add(widget)
                repaired++
            }
        }
        if (repaired > 0) {
            logger.warn(
                "PDF flatten: attached $repaired orphaned field widget(s) to page /Annots — " +
                    "the template is malformed (widgets missing from page annotations); " +
                    "without the repair flatten() would produce a blank document"
            )
        }
    }

    /**
     * Register [font] in the AcroForm's default resources and update every variable-text field
     * and its widget annotations to use it.
     *
     * Font sizes are capped at [MAX_FIELD_FONT_SIZE] to prevent oversized text when the template
     * declares large or auto (0) sizes. Auto-size (0) fields are set to the cap explicitly.
     *
     * PDF spec priority for Default Appearance: widget /DA > field /DA > AcroForm /DA.
     * We must update all three levels, otherwise the original (non-embedded) font reference
     * wins and Polish characters are garbled.
     */
    private fun applyFontToAcroForm(document: PDDocument, acroForm: PDAcroForm, font: PDType0Font) {
        val resources = acroForm.defaultResources ?: PDResources().also { acroForm.defaultResources = it }
        val fontKey = resources.add(font)
        val fontRef = "/${fontKey.name}"

        acroForm.defaultAppearance = "$fontRef $MAX_FIELD_FONT_SIZE Tf 0 g"

        for (field in acroForm.fieldTree) {
            if (field is org.apache.pdfbox.pdmodel.interactive.form.PDVariableText) {
                val fieldSize = cappedFontSize(parseFontSize(field.defaultAppearance))
                field.defaultAppearance = "$fontRef $fieldSize Tf 0 g"

                for (widget in field.widgets) {
                    val widgetCos = widget.cosObject
                    if (widgetCos.containsKey(COSName.DA)) {
                        val widgetSize = cappedFontSize(parseFontSize(widgetCos.getString(COSName.DA)))
                        widgetCos.setString(COSName.DA, "$fontRef $widgetSize Tf 0 g")
                    }
                    widgetCos.removeItem(COSName.AP)
                }
            }
        }

        logger.info("PDF font setup: registered font as '${fontKey.name}'")
    }

    /**
     * Returns the font size capped at [MAX_FIELD_FONT_SIZE].
     * Auto-size (0) is replaced with the cap so every field has a consistent, readable size.
     */
    private fun cappedFontSize(size: String): String {
        val pt = size.toFloatOrNull() ?: 0f
        return if (pt == 0f || pt > MAX_FIELD_FONT_SIZE) MAX_FIELD_FONT_SIZE.toString() else size
    }

    /**
     * Extract the font size from a PDF Default Appearance string like "/Helv 10 Tf 0 g".
     * Returns "0" (auto-size) when the string is absent or unparseable.
     */
    private fun parseFontSize(da: String?): String {
        if (da.isNullOrBlank()) return "0"
        // DA format: /FontName <size> Tf [color operators]
        val match = Regex("""/\S+\s+([\d.]+)\s+Tf""").find(da)
        return match?.groupValues?.get(1) ?: "0"
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
                    flattenPreservingContent(document, acroForm)
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
