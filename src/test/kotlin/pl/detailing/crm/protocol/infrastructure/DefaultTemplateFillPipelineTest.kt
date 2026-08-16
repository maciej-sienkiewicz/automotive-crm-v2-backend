package pl.detailing.crm.protocol.infrastructure

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import java.io.ByteArrayInputStream

/**
 * End-to-end regression for the bundled default acceptance-protocol template:
 * fill it through the real PdfProcessingService pipeline (font substitution,
 * value fill, selective flatten) and assert what SignedDocumentComposer needs
 * afterwards — the 'signature' and 'company_signature' fields must survive
 * with usable widget rectangles at their designed positions.
 */
class DefaultTemplateFillPipelineTest {

    private val s3Client = mockk<S3Client>()
    private val service = PdfProcessingService(s3Client, "test-bucket")

    private val fieldValues = mapOf(
        "brand" to "Porsche",
        "model" to "911 Carrera",
        "licenseplate" to "WA 5512K",
        "mileage" to "48 350 km",
        "services" to "Korekta lakieru, powłoka ceramiczna",
        "remarks" to "Brak uwag",
        "fullname" to "Małgorzata Wiśniewska",
        "companyname" to "Test Studio",
        "phonenumber" to "+48 600 000 000",
        "email" to "test@example.pl",
        "tax" to "1234567890",
        "date" to "2026-08-16 09:30",
        "price" to "4 850,00 zł",
        "keys" to "Yes",
        "documents" to "Off",
        // Header-section fields (supplementary mappings)
        "protocolnumber" to "VIS-2026-00042",
        "receivedby" to "Jan Kowalski",
        "provider" to "Studio Detailingowe Test"
    )

    private fun runFillPipeline(): ByteArray {
        val templateBytes = javaClass.getResourceAsStream("/templates/protokol_przyjecia_pojazdu_default.pdf")!!
            .use { it.readBytes() }

        every { s3Client.getObject(any<GetObjectRequest>()) } answers {
            software.amazon.awssdk.core.ResponseInputStream(
                GetObjectResponse.builder().contentLength(templateBytes.size.toLong()).build(),
                AbortableInputStream.create(ByteArrayInputStream(templateBytes))
            )
        }
        val uploadedBytes = slot<RequestBody>()
        every { s3Client.putObject(any<PutObjectRequest>(), capture(uploadedBytes)) } returns
            PutObjectResponse.builder().build()

        service.fillPdfForm("template.pdf", fieldValues, "filled.pdf")

        val bytes = uploadedBytes.captured.contentStreamProvider().newStream().readAllBytes()
        // debug artifact for structural analysis outside the JVM
        System.getenv("FILL_DEBUG_OUT")?.let { java.io.File(it).writeBytes(bytes) }
        return bytes
    }

    @Test
    fun `signature fields survive fill and flatten with usable widget rectangles`() {
        val filled = runFillPipeline()

        Loader.loadPDF(filled).use { doc ->
            val acroForm = doc.documentCatalog.acroForm
            assertNotNull(acroForm, "filled PDF must still carry an AcroForm")

            val remainingFields = acroForm!!.fieldTree.map { it.fullyQualifiedName }
            println("Fields remaining after flatten: $remainingFields")

            for (name in listOf("signature", "company_signature")) {
                val field = acroForm.getField(name) as? PDTerminalField
                assertNotNull(field, "field '$name' must survive flatten, remaining=$remainingFields")
                val widget = field!!.widgets.firstOrNull()
                assertNotNull(widget, "field '$name' must keep its widget")
                val rect = widget!!.rectangle
                assertNotNull(rect, "field '$name' widget must keep its rectangle")
                val page = widget.page ?: doc.pages.firstOrNull { p ->
                    p.annotations.any { it.cosObject == widget.cosObject }
                }
                assertNotNull(page, "field '$name' widget must be attached to a page")
                assertTrue(rect!!.width > 60f && rect.height > 20f, "field '$name' rect too small: $rect")
                // Designed positions (top-based y ≈ 782.65 → PDF y ≈ 20.2..59.3)
                assertTrue(rect.lowerLeftY in 10f..80f, "field '$name' should sit at the bottom of the page, got $rect")
                println("field '$name': rect=$rect page=ok")
            }
        }
    }

    @Test
    fun `filled values are drawn into the flattened page content`() {
        val filled = runFillPipeline()

        Loader.loadPDF(filled).use { doc ->
            val text = PDFTextStripper().getText(doc)
            for (expected in listOf(
                "Porsche", "911 Carrera", "WA 5512K", "Małgorzata Wiśniewska", "4 850,00 zł",
                "VIS-2026-00042", "Jan Kowalski", "Studio Detailingowe Test"
            )) {
                assertTrue(text.contains(expected), "flattened page text must contain '$expected'")
            }
        }
    }
}
