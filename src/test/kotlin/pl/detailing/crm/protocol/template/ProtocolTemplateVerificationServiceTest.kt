package pl.detailing.crm.protocol.template

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.protocol.domain.ProtocolTemplateFormat
import java.io.ByteArrayOutputStream

class ProtocolTemplateVerificationServiceTest {

    private val service = ProtocolTemplateVerificationService()

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun buildPdf(
        fieldNames: List<String>,
        checkboxNames: Set<String> = setOf("keys", "documents"),
        signatureRect: PDRectangle = PDRectangle(50f, 50f, 260f, 40f)
    ): ByteArray {
        val document = PDDocument()
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)

        val acroForm = PDAcroForm(document)
        document.documentCatalog.acroForm = acroForm
        val resources = PDResources()
        resources.put(
            org.apache.pdfbox.cos.COSName.getPDFName("Helv"),
            PDType1Font(Standard14Fonts.FontName.HELVETICA)
        )
        acroForm.defaultResources = resources
        acroForm.defaultAppearance = "/Helv 7 Tf 0 g"

        var y = 750f
        fieldNames.forEach { name ->
            val field = if (name in checkboxNames) {
                PDCheckBox(acroForm).apply { partialName = name }
            } else {
                PDTextField(acroForm).apply {
                    partialName = name
                    defaultAppearance = "/Helv 7 Tf 0 g"
                }
            }
            val widget = field.widgets[0]
            widget.rectangle =
                if (name.lowercase() in setOf("signature", "company_signature")) signatureRect
                else PDRectangle(50f, y, 200f, 18f)
            widget.page = page
            page.annotations.add(widget)
            acroForm.fields = acroForm.fields + field
            y -= 24f
        }

        return ByteArrayOutputStream().use { out ->
            document.save(out)
            document.close()
            out.toByteArray()
        }
    }

    private fun allRequiredFieldNames() = ProtocolTemplateVerificationService.requiredFieldNames()

    // ─── PDF ──────────────────────────────────────────────────────────────────

    @Test
    fun `PDF with all required fields is valid`() {
        val pdf = buildPdf(allRequiredFieldNames())
        val result = service.verify(pdf, ProtocolTemplateFormat.PDF)

        assertTrue(result.isValid, "expected valid, got missing=${result.missingFields} problems=${result.problems}")
        assertEquals(emptyList<String>(), result.missingFields)
    }

    @Test
    fun `PDF field names match case-insensitively`() {
        val pdf = buildPdf(allRequiredFieldNames().map { it.uppercase() })
        val result = service.verify(pdf, ProtocolTemplateFormat.PDF)

        assertTrue(result.isValid, "uppercase field names should verify: ${result.missingFields}")
    }

    @Test
    fun `PDF with missing fields is rejected and reports them`() {
        val withoutBrandAndSignature = allRequiredFieldNames() - setOf("brand", "signature")
        val pdf = buildPdf(withoutBrandAndSignature)
        val result = service.verify(pdf, ProtocolTemplateFormat.PDF)

        assertFalse(result.isValid)
        assertTrue("brand" in result.missingFields)
        assertTrue("signature" in result.missingFields)
    }

    @Test
    fun `PDF without AcroForm is rejected with explanation`() {
        val document = PDDocument()
        document.addPage(PDPage(PDRectangle.A4))
        val bytes = ByteArrayOutputStream().use { out ->
            document.save(out)
            document.close()
            out.toByteArray()
        }

        val result = service.verify(bytes, ProtocolTemplateFormat.PDF)

        assertFalse(result.isValid)
        assertTrue(result.problems.any { "AcroForm" in it })
    }

    @Test
    fun `PDF with too small signature box is rejected`() {
        val pdf = buildPdf(allRequiredFieldNames(), signatureRect = PDRectangle(50f, 50f, 30f, 10f))
        val result = service.verify(pdf, ProtocolTemplateFormat.PDF)

        assertFalse(result.isValid)
        assertTrue(result.problems.any { "za mał" in it }, "expected signature box problem, got: ${result.problems}")
    }

    @Test
    fun `garbage bytes are rejected as unparseable PDF`() {
        val result = service.verify("definitely not a pdf".toByteArray(), ProtocolTemplateFormat.PDF)

        assertFalse(result.isValid)
        assertTrue(result.problems.isNotEmpty())
    }

    @Test
    fun `bundled default template PDF passes verification`() {
        val bytes = javaClass.getResourceAsStream("/templates/protokol_przyjecia_pojazdu_default.pdf")!!
            .use { it.readBytes() }
        val result = service.verify(bytes, ProtocolTemplateFormat.PDF)

        assertTrue(result.isValid, "bundled default template must verify: missing=${result.missingFields} problems=${result.problems}")
        assertTrue("company_signature" in result.optionalFieldsFound)
    }

    // ─── HTML ─────────────────────────────────────────────────────────────────

    @Test
    fun `HTML with all data-field placeholders is valid`() {
        val placeholders = allRequiredFieldNames()
            .joinToString("\n") { """<div class="box" data-field="$it"></div>""" }
        val html = "<!DOCTYPE html><html><body>$placeholders</body></html>"

        val result = service.verify(html.toByteArray(), ProtocolTemplateFormat.HTML)

        assertTrue(result.isValid, "expected valid, got missing=${result.missingFields}")
    }

    @Test
    fun `HTML missing placeholders is rejected and reports them`() {
        val html = """<!DOCTYPE html><html><body><div data-field="brand"></div></body></html>"""

        val result = service.verify(html.toByteArray(), ProtocolTemplateFormat.HTML)

        assertFalse(result.isValid)
        assertTrue("signature" in result.missingFields)
        assertTrue("model" in result.missingFields)
        assertFalse("brand" in result.missingFields)
    }

    @Test
    fun `non-HTML text is rejected`() {
        val result = service.verify("just some text".toByteArray(), ProtocolTemplateFormat.HTML)

        assertFalse(result.isValid)
        assertTrue(result.problems.isNotEmpty())
    }

    @Test
    fun `bundled HTML template passes verification`() {
        val bytes = javaClass.getResourceAsStream("/templates/protokol_przyjecia_pojazdu.html")!!
            .use { it.readBytes() }
        val result = service.verify(bytes, ProtocolTemplateFormat.HTML)

        assertTrue(result.isValid, "bundled HTML template must verify: missing=${result.missingFields}")
    }
}
