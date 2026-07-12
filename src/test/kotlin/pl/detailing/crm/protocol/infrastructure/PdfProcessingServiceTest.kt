package pl.detailing.crm.protocol.infrastructure

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox
import org.apache.pdfbox.pdmodel.interactive.form.PDField
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Tests for [PdfProcessingService], focused on the check-in vehicle receipt protocol flow:
 * template download -> form fill -> flatten -> upload.
 *
 * Regression background: the template PDF uploaded to the production S3 bucket had its field
 * widget annotations missing from the pages' /Annots arrays (and without /P page references —
 * PDFBox logged "There has been a widget with a missing page reference"). PDFBox's flatten()
 * only draws widgets found in page /Annots and then removes ALL fields, so the served
 * protocol was visually blank — despite the logs reporting "15/15 fields set". The local
 * template had properly attached widgets, which is why the bug only reproduced on the server.
 */
class PdfProcessingServiceTest {

    private val s3Client = mockk<S3Client>()
    private val service = PdfProcessingService(s3Client, "test-bucket")

    companion object {
        private val SIGNATURE_RECT = PDRectangle(300f, 100f, 200f, 60f)
    }

    private val protocolFields = mapOf(
        "customer_name" to "Jan Kowalski",
        "vehicle_make" to "Škoda Octavia",
        "registration" to "WX 12345",
        "notes" to "Zażółć gęślą jaźń — rysa na błotniku"
    )

    // ---------------------------------------------------------------------------------------
    // fillPdfForm
    // ---------------------------------------------------------------------------------------

    @Test
    fun `template with widgets missing from page Annots produces a non-blank flattened PDF (production regression)`() {
        // Exact production scenario: widgets not in /Annots, no /P page reference,
        // NeedAppearances=true. Without the orphan-widget repair flatten() silently
        // drops every filled value and the protocol PDF is blank.
        val template = createTemplatePdf(
            needAppearances = true,
            widgetsInAnnots = false,
            widgetsWithPageRef = false
        )
        val output = fillViaService(template, protocolFields)

        val text = extractText(output)
        protocolFields.values.forEach { value ->
            assertTrue(text.contains(value), "Flattened PDF must contain '$value' but text was:\n$text")
        }
        assertFlattened(output)
    }

    @Test
    fun `template with page-referenced widgets missing from Annots is also repaired`() {
        val template = createTemplatePdf(
            needAppearances = false,
            widgetsInAnnots = false,
            widgetsWithPageRef = true
        )
        val output = fillViaService(template, protocolFields)

        val text = extractText(output)
        protocolFields.values.forEach { value ->
            assertTrue(text.contains(value), "Flattened PDF must contain '$value' but text was:\n$text")
        }
        assertFlattened(output)
    }

    @Test
    fun `template with NeedAppearances=true produces a non-blank flattened PDF`() {
        val template = createTemplatePdf(needAppearances = true)
        val output = fillViaService(template, protocolFields)

        val text = extractText(output)
        protocolFields.values.forEach { value ->
            assertTrue(text.contains(value), "Flattened PDF must contain '$value' but text was:\n$text")
        }
        assertFlattened(output)
    }

    @Test
    fun `template without NeedAppearances is filled and flattened correctly (local scenario)`() {
        val template = createTemplatePdf(needAppearances = false)
        val output = fillViaService(template, protocolFields)

        val text = extractText(output)
        protocolFields.values.forEach { value ->
            assertTrue(text.contains(value), "Flattened PDF must contain '$value' but text was:\n$text")
        }
        assertFlattened(output)
    }

    @Test
    fun `polish diacritics survive filling and flattening`() {
        val template = createTemplatePdf(needAppearances = true)
        val output = fillViaService(template, mapOf("notes" to "ąćęłńóśźż ĄĆĘŁŃÓŚŹŻ"))

        assertTrue(extractText(output).contains("ąćęłńóśźż ĄĆĘŁŃÓŚŹŻ"))
    }

    @Test
    fun `checkbox is checked for YES and unchecked for other values without breaking flatten`() {
        listOf("YES", "on", "TRUE", "1").forEach { value ->
            val template = createTemplatePdf(needAppearances = true, withCheckbox = true)
            // Must not throw and must still render the text fields
            val output = fillViaService(template, protocolFields + ("keys_left" to value))
            assertTrue(extractText(output).contains("Jan Kowalski"))
            assertFlattened(output)
        }

        val template = createTemplatePdf(needAppearances = true, withCheckbox = true)
        val output = fillViaService(template, protocolFields + ("keys_left" to "no"))
        assertTrue(extractText(output).contains("Jan Kowalski"))
    }

    @Test
    fun `field names missing from the template are skipped without failing the whole protocol`() {
        val template = createTemplatePdf(needAppearances = true)
        val output = fillViaService(
            template,
            protocolFields + ("field_that_does_not_exist" to "whatever")
        )

        assertTrue(extractText(output).contains("Jan Kowalski"))
        assertFlattened(output)
    }

    @Test
    fun `signature field survives filling un-flattened so the signing step can find its rectangle`() {
        val template = createTemplatePdf(needAppearances = true, withSignatureField = true)
        val output = fillViaService(template, protocolFields)

        // Other fields flattened, their values in static content
        val text = extractText(output)
        protocolFields.values.forEach { value ->
            assertTrue(text.contains(value), "Flattened PDF must contain '$value'")
        }

        Loader.loadPDF(output).use { doc ->
            val acroForm = doc.documentCatalog.acroForm
            val remaining = acroForm?.fields?.map { it.fullyQualifiedName } ?: emptyList()
            assertEquals(listOf("signature"), remaining, "Only the signature field may stay interactive")

            val widget = (acroForm!!.getField("signature")
                as org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField).widgets.first()
            assertEquals(SIGNATURE_RECT.lowerLeftX, widget.rectangle.lowerLeftX)
            assertEquals(SIGNATURE_RECT.width, widget.rectangle.width)
            // The repair step must leave the widget reachable for the composer
            val page = doc.getPage(0)
            assertTrue(
                page.annotations.any { it.cosObject == widget.cosObject },
                "Signature widget must be listed in page /Annots"
            )
        }
    }

    @Test
    fun `orphaned signature widget is repaired and still not flattened`() {
        val template = createTemplatePdf(
            needAppearances = true,
            withSignatureField = true,
            widgetsInAnnots = false,
            widgetsWithPageRef = false
        )
        val output = fillViaService(template, protocolFields)

        assertTrue(extractText(output).contains("Jan Kowalski"))
        Loader.loadPDF(output).use { doc ->
            val remaining = doc.documentCatalog.acroForm?.fields?.map { it.fullyQualifiedName }
            assertEquals(listOf("signature"), remaining)
        }
    }

    @Test
    fun `pdf without an AcroForm is rejected`() {
        val noFormPdf = PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
        stubS3Download(noFormPdf)

        assertThrows(IllegalArgumentException::class.java) {
            service.fillPdfForm("templates/protocol.pdf", protocolFields, "out/protocol.pdf")
        }
    }

    @Test
    fun `fillPdfForm uploads to the requested output key and returns it`() {
        val template = createTemplatePdf(needAppearances = false)
        stubS3Download(template)
        val putRequest = slot<PutObjectRequest>()
        every { s3Client.putObject(capture(putRequest), any<RequestBody>()) } returns
            PutObjectResponse.builder().build()

        val result = service.fillPdfForm("templates/protocol.pdf", protocolFields, "out/protocol.pdf")

        assertEquals("out/protocol.pdf", result)
        assertEquals("out/protocol.pdf", putRequest.captured.key())
        assertEquals("test-bucket", putRequest.captured.bucket())
        assertEquals("application/pdf", putRequest.captured.contentType())
    }

    // ---------------------------------------------------------------------------------------
    // flattenPdfBytes
    // ---------------------------------------------------------------------------------------

    @Test
    fun `flattenPdfBytes preserves values of an interactive form with NeedAppearances=true`() {
        // Simulates a still-interactive PDF whose values have no appearance streams:
        // with NeedAppearances=true PDFBox's setValue() skips generating them.
        val interactive = PDDocument().use { doc ->
            val bytes = createTemplatePdf(needAppearances = true)
            Loader.loadPDF(bytes).use { loaded ->
                val form = loaded.documentCatalog.acroForm!!
                form.getField("customer_name").setValue("Anna Nowak")
                ByteArrayOutputStream().also { loaded.save(it) }.toByteArray()
            }
        }

        val flattened = service.flattenPdfBytes(interactive)

        assertTrue(extractText(flattened).contains("Anna Nowak"))
        assertFlattened(flattened)
    }

    @Test
    fun `flattenPdfBytes handles a PDF without an AcroForm`() {
        val noFormPdf = PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

        val flattened = service.flattenPdfBytes(noFormPdf)

        Loader.loadPDF(flattened).use { doc -> assertEquals(1, doc.numberOfPages) }
    }

    // ---------------------------------------------------------------------------------------
    // signAndFlattenPdf
    // ---------------------------------------------------------------------------------------

    @Test
    fun `signAndFlattenPdf overlays the signature image and keeps the filled content`() {
        val filledPdf = fillViaService(createTemplatePdf(needAppearances = true), protocolFields)
        val downloads = ArrayDeque(listOf(filledPdf, tinyPngBytes()))
        every { s3Client.getObject(any<GetObjectRequest>()) } answers {
            responseStream(downloads.removeFirst())
        }
        val uploaded = slot<RequestBody>()
        every { s3Client.putObject(any<PutObjectRequest>(), capture(uploaded)) } returns
            PutObjectResponse.builder().build()

        val result = service.signAndFlattenPdf("out/protocol.pdf", "signatures/sig.png", "out/signed.pdf")

        assertEquals("out/signed.pdf", result)
        val signedBytes = uploaded.captured.contentStreamProvider().newStream().readAllBytes()
        assertTrue(extractText(signedBytes).contains("Jan Kowalski"))
        Loader.loadPDF(signedBytes).use { doc ->
            val lastPage = doc.getPage(doc.numberOfPages - 1)
            val hasImage = lastPage.resources.xObjectNames.asSequence()
                .any { lastPage.resources.isImageXObject(it) }
            assertTrue(hasImage, "Signed PDF must contain the signature image XObject")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** Stubs S3, runs fillPdfForm and returns the bytes uploaded back to S3. */
    private fun fillViaService(template: ByteArray, fields: Map<String, String>): ByteArray {
        stubS3Download(template)
        val uploaded = slot<RequestBody>()
        every { s3Client.putObject(any<PutObjectRequest>(), capture(uploaded)) } returns
            PutObjectResponse.builder().build()

        service.fillPdfForm("templates/protocol.pdf", fields, "out/protocol.pdf")

        return uploaded.captured.contentStreamProvider().newStream().readAllBytes()
    }

    private fun stubS3Download(bytes: ByteArray) {
        every { s3Client.getObject(any<GetObjectRequest>()) } answers { responseStream(bytes) }
    }

    private fun responseStream(bytes: ByteArray) =
        software.amazon.awssdk.core.ResponseInputStream(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(ByteArrayInputStream(bytes))
        )

    private fun extractText(pdfBytes: ByteArray): String =
        Loader.loadPDF(pdfBytes).use { PDFTextStripper().getText(it) }

    private fun assertFlattened(pdfBytes: ByteArray) {
        Loader.loadPDF(pdfBytes).use { doc ->
            val fields = doc.documentCatalog.acroForm?.fields ?: emptyList()
            assertTrue(fields.isEmpty(), "Flattened PDF must not contain interactive form fields")
        }
    }

    /**
     * Builds an in-memory protocol template mirroring the real one: an AcroForm with text
     * fields (and optionally a checkbox).
     *
     * [widgetsInAnnots]=false and [widgetsWithPageRef]=false reproduce the malformed
     * production template whose widgets were not listed in the page's /Annots array and
     * had no /P page reference.
     */
    private fun createTemplatePdf(
        needAppearances: Boolean,
        withCheckbox: Boolean = false,
        withSignatureField: Boolean = false,
        widgetsInAnnots: Boolean = true,
        widgetsWithPageRef: Boolean = true
    ): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)

            val acroForm = PDAcroForm(doc)
            doc.documentCatalog.acroForm = acroForm
            val resources = PDResources()
            resources.put(COSName.getPDFName("Helv"), PDType1Font(Standard14Fonts.FontName.HELVETICA))
            acroForm.defaultResources = resources
            acroForm.defaultAppearance = "/Helv 0 Tf 0 g"
            acroForm.needAppearances = needAppearances

            val fields = mutableListOf<PDField>()
            var y = 750f
            protocolFields.keys.forEach { name ->
                val field = PDTextField(acroForm)
                field.partialName = name
                field.defaultAppearance = "/Helv 10 Tf 0 g"
                val widget = field.widgets[0]
                widget.rectangle = PDRectangle(50f, y, 400f, 20f)
                if (widgetsWithPageRef) widget.page = page
                if (widgetsInAnnots) page.annotations.add(widget)
                fields.add(field)
                y -= 30f
            }

            if (withSignatureField) {
                val field = PDTextField(acroForm)
                field.partialName = "signature"
                field.defaultAppearance = "/Helv 10 Tf 0 g"
                val widget = field.widgets[0]
                widget.rectangle = SIGNATURE_RECT
                if (widgetsWithPageRef) widget.page = page
                if (widgetsInAnnots) page.annotations.add(widget)
                fields.add(field)
            }

            if (withCheckbox) {
                val checkBox = PDCheckBox(acroForm)
                checkBox.partialName = "keys_left"
                val widget = checkBox.widgets[0]
                widget.rectangle = PDRectangle(50f, y, 15f, 15f)
                widget.page = page

                // A checkbox needs named appearance states ("Yes"/"Off") for onValues to work
                val normal = COSDictionary()
                listOf("Yes", "Off").forEach { state ->
                    val stream = PDAppearanceStream(doc)
                    stream.bBox = PDRectangle(15f, 15f)
                    normal.setItem(COSName.getPDFName(state), stream)
                }
                val appearance = PDAppearanceDictionary()
                appearance.cosObject.setItem(COSName.N, normal)
                widget.appearance = appearance
                checkBox.value = "Off"

                page.annotations.add(widget)
                fields.add(checkBox)
            }

            acroForm.fields = fields
            return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    private fun tinyPngBytes(): ByteArray {
        val image = BufferedImage(10, 5, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
