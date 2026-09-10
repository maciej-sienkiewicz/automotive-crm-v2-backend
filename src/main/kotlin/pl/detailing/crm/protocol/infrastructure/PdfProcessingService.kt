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
import pl.detailing.crm.studio.logo.DocumentLogoPlacement
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Jak wyglądają wartości wpisywane w pola formularza: font (zasób classpath, TTF),
 * rozmiar w pt i kolor jako operator DA („0 g" = czerń, „r g b rg" = RGB 0..1).
 */
data class FieldTypography(
    val fontResource: String,
    val fontSize: Float,
    val colorOperator: String
) {
    companion object {
        /**
         * Wpisy mają wyglądać jak część dokumentu, nie jak naklejka: ten sam krój co
         * etykiety i tytuły szablonów (Inter), rozmiar zbliżony do etykiet (9 pt) i
         * kolor tuszu dokumentu (#080606, `--ink` w szablonach HTML). Poprzednio:
         * Liberation Sans 7 pt w czystej czerni, przez co użytkownicy mówili o
         * „sztucznie wklejonej czcionce".
         */
        val DEFAULT = FieldTypography("/fonts/Inter-Regular.ttf", 8.5f, "0.031 0.024 0.024 rg")

        /** Wygląd sprzed zmiany, zachowany do porównań i testów regresji. */
        val LEGACY = FieldTypography("/fonts/LiberationSans-Regular.ttf", 7f, "0 g")
    }
}

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
        /**
         * The AcroForm field reserved in protocol templates for the customer's signature.
         * It must survive form filling un-flattened: SignedDocumentComposer reads its widget
         * rectangle to know where (and how large) the tablet signature image may be stamped.
         */
        const val SIGNATURE_FIELD_NAME = "signature"

        /**
         * AcroForm field for the employee's own (company-side) signature.
         * Preserved alongside [SIGNATURE_FIELD_NAME] so SignedDocumentComposer can stamp
         * the configured user signature into the correct position.
         */
        const val COMPANY_SIGNATURE_FIELD_NAME = "company_signature"
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
    /**
     * @param logoPng studio logo (PNG, print variant) stamped into the reserved header
     *        slot of page 1 — see [DocumentLogoPlacement]; null = no logo
     */
    fun fillPdfForm(
        templateS3Key: String,
        fieldMappings: Map<String, String>,
        outputS3Key: String,
        logoPng: ByteArray? = null
    ): String {
        // Download template from S3
        val downloadStart = System.currentTimeMillis()
        val templateBytes = downloadFromS3(templateS3Key)
        logger.info("[PERF]     - S3 download template: ${System.currentTimeMillis() - downloadStart}ms (${templateBytes.size} bytes)")

        // Fill the form
        val fillStart = System.currentTimeMillis()
        val filledPdfBytes = fillForm(templateBytes, fieldMappings, logoPng)
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
    /**
     * Wypełnia formularz w pamięci — bez pobierania szablonu z S3 i bez odkładania
     * wyniku z powrotem. Używane tam, gdzie szablon jest wbudowany w aplikację
     * (upoważnienie dla operatora SMS), a gotowy plik trafia w inne miejsce niż
     * dokumenty wizyty. Pole podpisu zostaje interaktywne, tak jak w [fillPdfForm].
     */
    fun fillFormInMemory(
        pdfBytes: ByteArray,
        fieldMappings: Map<String, String>,
        logoPng: ByteArray? = null,
        typography: FieldTypography = FieldTypography.DEFAULT
    ): ByteArray = fillForm(pdfBytes, fieldMappings, logoPng, typography)

    private fun fillForm(
        pdfBytes: ByteArray,
        fieldMappings: Map<String, String>,
        logoPng: ByteArray?,
        typography: FieldTypography = FieldTypography.DEFAULT
    ): ByteArray {
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
                val fieldFont = setupUnicodeFontForForm(document, acroForm, typography)
                val fontEmbedded = fieldFont != null

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
                                // Rozmiar z typografii jest bazą; wartość, która się nie mieści
                                // (długa nazwa usługodawcy, cztery linie usług), schodzi w dół
                                // o 0,5 pt, aż wejdzie w pole. Bez tego tekst jest obcinany.
                                if (fieldFont != null && field is org.apache.pdfbox.pdmodel.interactive.form.PDVariableText) {
                                    val size = fittingFontSize(field, value, fieldFont.font, typography.fontSize)
                                    if (size != typography.fontSize) {
                                        applyDefaultAppearance(field, "${fieldFont.reference} $size Tf ${typography.colorOperator}")
                                    }
                                }
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

                if (logoPng != null) stampLogo(document, logoPng)

                // Flatten all fields into static page content so the PDF renders
                // correctly in every viewer (including pdf.js canvas rendering on
                // the signing tablet) — EXCEPT the signature field. Its widget rectangle
                // must survive: SignedDocumentComposer uses it to position and scale the
                // signature image stamped after tablet signing. Flattening it would erase
                // the field and force the composer onto its blind fallback position.
                flattenPreservingContent(document, acroForm, keepFieldNames = setOf(SIGNATURE_FIELD_NAME, COMPANY_SIGNATURE_FIELD_NAME))

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
    /** Font osadzony w formularzu i jego nazwa w słowniku zasobów (do składania DA). */
    private class EmbeddedFieldFont(val font: PDType0Font, val reference: String)

    private fun setupUnicodeFontForForm(
        document: PDDocument,
        acroForm: PDAcroForm,
        typography: FieldTypography = FieldTypography.DEFAULT
    ): EmbeddedFieldFont? {
        val classpathFonts = listOf(
            typography.fontResource,
            "/fonts/LiberationSans-Regular.ttf",
            "/fonts/DejaVuSans.ttf"
        ).distinct()
        logger.info("PDF font setup: trying classpath fonts first: $classpathFonts")
        for (classpathFont in classpathFonts) {
            val stream = javaClass.getResourceAsStream(classpathFont)
            logger.info("PDF font setup: classpath '$classpathFont' — found=${stream != null}")
            stream?.use {
                try {
                    /*
                     * embedSubset MUSI być false na tej ścieżce.
                     *
                     * Wypełnianie pól idzie przez AppearanceGeneratorHelper (setValue),
                     * a ten NIE współpracuje z fontem subsetowym: appearance streamy
                     * dostają kody glifów pełnego fontu, ale font nigdy nie trafia do
                     * rejestru fontsToSubset dokumentu (robi to tylko PDPageContentStream),
                     * więc save() nie zapisuje pliku fontu wcale. Czytnik podstawia
                     * wtedy własny font pod kody Identity-H i tekst wychodzi jako
                     * "chińskie znaczki". Objaw w logu: seria WARN
                     * "attempting to use font ... that isn't embedded" przy wypełnianiu.
                     *
                     * Subset (true) jest w porządku w AuditTrailPageGenerator, bo tam
                     * tekst rysuje bezpośrednio PDPageContentStream — wspierana ścieżka.
                     * Rozmiar pliku trzeba odzyskiwać inaczej (mniejszy plik fontu),
                     * nie tym przełącznikiem.
                     */
                    val font = PDType0Font.load(document, it, false)
                    val reference = applyFontToAcroForm(document, acroForm, font, typography)
                    logger.info("PDF font setup: SUCCESS — loaded classpath font '$classpathFont'")
                    return EmbeddedFieldFont(font, reference)
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
                    // embedSubset=false — jak wyżej: form-fill nie znosi subsetu.
                    val font = PDType0Font.load(document, java.io.FileInputStream(file), false)
                    val reference = applyFontToAcroForm(document, acroForm, font, typography)
                    logger.info("PDF font setup: SUCCESS — loaded system font '$path'")
                    return EmbeddedFieldFont(font, reference)
                } catch (e: Exception) {
                    logger.warn("PDF font setup: failed to load system font '$path': ${e.message}")
                }
            }
        }

        logger.warn("PDF font setup: FALLBACK — no Unicode font found. Using Helvetica + needAppearances=true. Polish characters WILL be garbled in flattened PDFs.")
        acroForm.needAppearances = true
        acroForm.defaultAppearance = "/Helv 0 Tf 0 g"
        return null
    }

    /** Najmniejszy rozmiar, do którego schodzi [fittingFontSize]; poniżej wpis jest nieczytelny na wydruku. */
    private val minFieldFontSize = 6f

    /**
     * Największy rozmiar ≤ [base] (krok 0,5 pt), przy którym [value] mieści się w polu.
     * Liczy tak, jak układa tekst PDFBox: wcięcie 2 pt z każdej strony, interlinia =
     * wysokość bounding boxu fontu, pola wieloliniowe łamane po słowach.
     */
    internal fun fittingFontSize(
        field: org.apache.pdfbox.pdmodel.interactive.form.PDVariableText,
        value: String,
        font: PDType0Font,
        base: Float
    ): Float {
        val rect = field.widgets.firstOrNull()?.rectangle ?: return base
        val inset = 2f
        val availableWidth = rect.width - 2 * inset
        val availableHeight = rect.height - 2 * inset
        if (availableWidth <= 0f || availableHeight <= 0f) return base
        val textField = field as? org.apache.pdfbox.pdmodel.interactive.form.PDTextField
        val multiline = textField?.isMultiline == true

        // Pole jednoliniowe, w którym wartość nie mieści się w bazowym rozmiarze, a jest
        // dość wysokie na dwie linie (USŁUGODAWCA w protokole wydania: 30 pt): lepiej
        // złamać tekst niż zmniejszać go do nieczytelności albo obcinać. Flaga zmienia
        // się na kopii wypełnianej w pamięci, szablony w S3 zostają nietknięte.
        if (!multiline && textField != null &&
            !fits(value, font, base, availableWidth, availableHeight, multiline = false) &&
            2 * lineHeight(font, base) <= availableHeight
        ) {
            textField.isMultiline = true
            return fittingFontSize(field, value, font, base)
        }

        var size = base
        while (size > minFieldFontSize) {
            if (fits(value, font, size, availableWidth, availableHeight, multiline)) return size
            size -= 0.5f
        }
        return minFieldFontSize
    }

    private fun lineHeight(font: PDType0Font, size: Float): Float = font.boundingBox.height / 1000f * size

    private fun fits(value: String, font: PDType0Font, size: Float, width: Float, height: Float, multiline: Boolean): Boolean {
        val lineHeight = lineHeight(font, size)
        fun textWidth(text: String): Float = try {
            font.getStringWidth(text) / 1000f * size
        } catch (e: Exception) {
            0f // glif spoza fontu: PDFBox i tak go pominie, nie blokujemy dopasowania
        }
        if (!multiline) {
            return lineHeight <= height && textWidth(value) <= width
        }
        var lines = 0
        for (paragraph in value.split('\n')) {
            var current = ""
            for (word in paragraph.split(' ')) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (textWidth(candidate) <= width || current.isEmpty()) {
                    current = candidate
                } else {
                    lines++
                    current = word
                }
            }
            lines++
        }
        return lines * lineHeight <= height
    }

    private fun applyDefaultAppearance(field: org.apache.pdfbox.pdmodel.interactive.form.PDVariableText, da: String) {
        field.defaultAppearance = da
        for (widget in field.widgets) {
            val widgetCos = widget.cosObject
            if (widgetCos.containsKey(COSName.DA)) widgetCos.setString(COSName.DA, da)
        }
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
     * Ensure every field widget annotation is referenced by a page's /Annots array
     * AND carries a /P page reference.
     *
     * A widget that no page references cannot be rendered by any viewer and is silently
     * skipped (then deleted) by flatten(). Orphaned widgets are attached to the page they
     * point to via /P, or to the first page when the reference is missing (protocol
     * templates are single-page).
     *
     * The /P repair is just as critical as the /Annots one: PDFBox's
     * PDAcroForm.flatten(fields, ...) builds its page→widget map from /P references,
     * and when ANY widget of the fields being flattened lacks /P it falls back to
     * treating EVERY widget annotation on every page as a flatten target — which
     * strips the kept signature fields' widgets from the page. The signature then
     * gets stamped at the blind fallback position (bottom-left) and the company
     * signature is dropped entirely. Templates authored by tools that omit /P
     * (e.g. PyMuPDF) trigger exactly this without the repair.
     */
    private fun attachOrphanWidgetsToPages(document: PDDocument, acroForm: PDAcroForm) {
        if (document.numberOfPages == 0) return

        val widgetToPage: Map<org.apache.pdfbox.cos.COSDictionary, PDPage> = buildMap {
            for (page in document.pages) {
                for (annotation in page.annotations) {
                    put(annotation.cosObject, page)
                }
            }
        }

        var attached = 0
        var pageRefsRepaired = 0
        for (field in acroForm.fieldTree) {
            if (field !is org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField) continue
            for (widget in field.widgets) {
                val annotatedPage = widgetToPage[widget.cosObject]
                if (annotatedPage == null) {
                    val page = widget.page ?: document.getPage(0).also { widget.page = it }
                    page.annotations.add(widget)
                    attached++
                } else if (widget.page == null) {
                    widget.page = annotatedPage
                    pageRefsRepaired++
                }
            }
        }
        if (attached > 0) {
            logger.warn(
                "PDF flatten: attached $attached orphaned field widget(s) to page /Annots — " +
                    "the template is malformed (widgets missing from page annotations); " +
                    "without the repair flatten() would produce a blank document"
            )
        }
        if (pageRefsRepaired > 0) {
            logger.warn(
                "PDF flatten: repaired $pageRefsRepaired widget /P page reference(s) — " +
                    "without the repair flatten() would strip the preserved signature " +
                    "field widgets from the page"
            )
        }
    }

    /**
     * Register [font] in the AcroForm's default resources and update every variable-text field
     * and its widget annotations to use it.
     *
     * Every field gets the size and colour from [typography]; the sizes declared in the
     * template (including auto = 0) are ignored, so all values share one consistent look.
     *
     * PDF spec priority for Default Appearance: widget /DA > field /DA > AcroForm /DA.
     * We must update all three levels, otherwise the original (non-embedded) font reference
     * wins and Polish characters are garbled.
     */
    private fun applyFontToAcroForm(
        document: PDDocument,
        acroForm: PDAcroForm,
        font: PDType0Font,
        typography: FieldTypography = FieldTypography.DEFAULT
    ): String {
        val resources = acroForm.defaultResources ?: PDResources().also { acroForm.defaultResources = it }
        val fontKey = resources.add(font)
        val fontRef = "/${fontKey.name}"

        // Jeden rozmiar dla wszystkich pól. Rozmiar zapisany w szablonie (DA) to wartość
        // z edytora formularza, nie decyzja projektowa: „auto" (0) dawał każdemu polu
        // inną wielkość, a stałe 7 pt było tylko górnym limitem, przez który nigdy nie
        // dało się wartości powiększyć. O wyglądzie wpisów decyduje [FieldTypography].
        val da = "$fontRef ${typography.fontSize} Tf ${typography.colorOperator}"
        acroForm.defaultAppearance = da

        for (field in acroForm.fieldTree) {
            if (field is org.apache.pdfbox.pdmodel.interactive.form.PDVariableText) {
                field.defaultAppearance = da

                for (widget in field.widgets) {
                    val widgetCos = widget.cosObject
                    if (widgetCos.containsKey(COSName.DA)) {
                        widgetCos.setString(COSName.DA, da)
                    }
                    widgetCos.removeItem(COSName.AP)
                }
            }
        }

        logger.info("PDF font setup: registered font as '${fontKey.name}'")
        return fontRef
    }

    /**
     * Add a signature image to the last page of a PDF and flatten it.
     *
     * Flattening makes the PDF immutable by merging form fields into the content stream.
     */
    /**
     * Podgląd szablonu z logo: ten sam stempel co przy wypełnianiu, ale bez dotykania
     * pól formularza — użytkownik ogląda w ustawieniach dokładnie ten układ, który
     * dostanie klient, a pusty szablon zostaje pustym szablonem.
     */
    fun stampLogoForPreview(pdfBytes: ByteArray, logoPng: ByteArray): ByteArray =
        Loader.loadPDF(pdfBytes).use { document ->
            stampLogo(document, logoPng)
            ByteArrayOutputStream().use { out ->
                document.save(out)
                out.toByteArray()
            }
        }

    /**
     * Rysuje logo studia w zarezerwowanym slocie nagłówka pierwszej strony, przed
     * spłaszczeniem formularza — po nim logo jest zwykłą treścią strony i jedzie
     * z dokumentem przez podpis, pieczęć i wysyłkę bez żadnej dalszej obsługi.
     * Nieudany stempel nie może zablokować dokumentu: protokół bez logo to mniejsza
     * szkoda niż wizyta bez protokołu.
     */
    private fun stampLogo(document: PDDocument, logoPng: ByteArray) {
        if (document.numberOfPages == 0) return
        try {
            val page = document.getPage(0)
            val image = PDImageXObject.createFromByteArray(document, logoPng, "studio-logo")
            val box = DocumentLogoPlacement.fit(image.width, image.height, page.mediaBox.height)
            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.drawImage(image, box.x, box.y, box.width, box.height)
            }
            logger.info("Studio logo stamped at x=${box.x} y=${box.y} ${box.width}x${box.height}pt")
        } catch (e: Exception) {
            logger.warn("Could not stamp studio logo onto the document: ${e.message}", e)
        }
    }

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
