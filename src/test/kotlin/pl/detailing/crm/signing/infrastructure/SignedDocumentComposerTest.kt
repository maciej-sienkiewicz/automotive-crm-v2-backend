package pl.detailing.crm.signing.infrastructure

import io.mockk.every
import io.mockk.mockk
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.SignatureRequestId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitProtocolId
import pl.detailing.crm.signing.domain.SignatureRequest
import pl.detailing.crm.signing.domain.SignatureRequestStatus
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.imageio.ImageIO

/**
 * Tests the geometry of stamping the tablet signature into the protocol's "signature"
 * AcroForm field: the image must be scaled DOWN (never up) preserving aspect ratio and
 * centered inside the field's rectangle with an inner padding, so no stroke is ever
 * clipped by the field frame — regardless of how large the tablet canvas was.
 *
 * The drawn geometry is asserted by parsing the page content stream: drawImage emits a
 * `cm` matrix [width 0 0 height x y] directly before the image `Do` operator.
 */
class SignedDocumentComposerTest {

    private val auditTrailPageGenerator = mockk<AuditTrailPageGenerator> {
        every { appendAuditPage(any(), any(), any(), any(), any()) } returns Unit
    }
    private val composer = SignedDocumentComposer(auditTrailPageGenerator)

    private val fieldRect = PDRectangle(300f, 100f, 200f, 60f) // llx=300, lly=100, 200x60pt
    private val padding = 3f

    // ---------------------------------------------------------------------------------------

    @Test
    fun `oversized tablet signature is scaled down to fit entirely inside the signature field`() {
        val pdf = protocolPdfWithSignatureField()
        // Big tablet: 2000x600px, much larger than the 200x60pt field
        val signed = composer.compose(pdf, signaturePng(2000, 600), request(), emptyList(), "VIS-1", "seal")

        val (w, h, x, y) = drawnImageGeometry(signed)
        assertInsideField(w, h, x, y)
        assertAspectPreserved(2000f, 600f, w, h)
        // Height is the binding constraint: image aspect 2000/600 is wider than the box,
        // but 54/600 < 194/2000, so the scale is set by the available height (60 - 2*3)
        assertEquals(fieldRect.height - 2 * padding, h, 0.01f)
    }

    @Test
    fun `very wide signature is constrained by field width and centered vertically`() {
        val pdf = protocolPdfWithSignatureField()
        val signed = composer.compose(pdf, signaturePng(3000, 300), request(), emptyList(), "VIS-1", "seal")

        val (w, h, x, y) = drawnImageGeometry(signed)
        assertInsideField(w, h, x, y)
        assertAspectPreserved(3000f, 300f, w, h)
        // Centered: equal margins on both axes
        assertEquals(fieldRect.lowerLeftX + (fieldRect.width - w) / 2, x, 0.01f)
        assertEquals(fieldRect.lowerLeftY + (fieldRect.height - h) / 2, y, 0.01f)
    }

    @Test
    fun `very tall signature is constrained by field height`() {
        val pdf = protocolPdfWithSignatureField()
        val signed = composer.compose(pdf, signaturePng(400, 800), request(), emptyList(), "VIS-1", "seal")

        val (w, h, x, y) = drawnImageGeometry(signed)
        assertInsideField(w, h, x, y)
        assertAspectPreserved(400f, 800f, w, h)
        assertEquals(fieldRect.height - 2 * padding, h, 0.01f)
    }

    @Test
    fun `small signature is not upscaled, only centered`() {
        val pdf = protocolPdfWithSignatureField()
        val signed = composer.compose(pdf, signaturePng(50, 20), request(), emptyList(), "VIS-1", "seal")

        val (w, h, x, y) = drawnImageGeometry(signed)
        assertEquals(50f, w, 0.01f)
        assertEquals(20f, h, 0.01f)
        assertEquals(fieldRect.lowerLeftX + (fieldRect.width - 50f) / 2, x, 0.01f)
        assertEquals(fieldRect.lowerLeftY + (fieldRect.height - 20f) / 2, y, 0.01f)
    }

    @Test
    fun `signature field is flattened away in the final signed document`() {
        val pdf = protocolPdfWithSignatureField()
        val signed = composer.compose(pdf, signaturePng(800, 300), request(), emptyList(), "VIS-1", "seal")

        Loader.loadPDF(signed).use { doc ->
            val fields = doc.documentCatalog.acroForm?.fields ?: emptyList()
            assertTrue(fields.isEmpty(), "Signed PDF must not contain interactive fields")
        }
    }

    @Test
    fun `falls back to bottom of last page when the signature field is absent`() {
        val pdf = PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
        val signed = composer.compose(pdf, signaturePng(2000, 600), request(), emptyList(), "VIS-1", "seal")

        val (w, h, x, y) = drawnImageGeometry(signed)
        // Fallback box: (50, 50) 200x80pt — the image must stay inside it
        assertTrue(x >= 50f && y >= 50f && x + w <= 250f && y + h <= 130f) {
            "Signature must fit the fallback box, got ${w}x$h at ($x, $y)"
        }
        assertAspectPreserved(2000f, 600f, w, h)
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun assertInsideField(w: Float, h: Float, x: Float, y: Float) {
        assertTrue(x >= fieldRect.lowerLeftX, "left edge clipped: x=$x")
        assertTrue(y >= fieldRect.lowerLeftY, "bottom edge clipped: y=$y")
        assertTrue(x + w <= fieldRect.upperRightX + 0.01f, "right edge clipped: x+w=${x + w}")
        assertTrue(y + h <= fieldRect.upperRightY + 0.01f, "top edge clipped: y+h=${y + h}")
    }

    private fun assertAspectPreserved(imgW: Float, imgH: Float, drawnW: Float, drawnH: Float) {
        assertEquals(imgW / imgH, drawnW / drawnH, 0.001f, "Aspect ratio must be preserved")
    }

    private fun request() = SignatureRequest(
        id = SignatureRequestId.random(),
        studioId = StudioId.random(),
        visitId = VisitId.random(),
        protocolId = VisitProtocolId.random(),
        tabletId = null,
        status = SignatureRequestStatus.DISPLAYED,
        documentS3Key = "protocols/x.pdf",
        documentSha256 = "0".repeat(64),
        documentName = "Protokół przyjęcia pojazdu",
        signerName = "Jan Kowalski",
        declarationText = "Oświadczam...",
        requestedBy = UserId.random(),
        requestedByName = "Anna Nowak",
        createdAt = Instant.now(),
        expiresAt = Instant.now().plusSeconds(600),
        displayedAt = Instant.now(),
        declarationAcceptedAt = null,
        signedAt = null,
        sealedAt = null,
        completedAt = null,
        signerIpAddress = null,
        signerDevice = null,
        signedPdfS3Key = null,
        sealApplied = false,
        timestampApplied = false,
        failureReason = null,
        updatedAt = Instant.now()
    )

    /** One-page PDF with an interactive "signature" text field at [fieldRect]. */
    private fun protocolPdfWithSignatureField(): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val acroForm = PDAcroForm(doc)
            doc.documentCatalog.acroForm = acroForm
            val resources = PDResources()
            resources.put(COSName.getPDFName("Helv"), PDType1Font(Standard14Fonts.FontName.HELVETICA))
            acroForm.defaultResources = resources
            acroForm.defaultAppearance = "/Helv 0 Tf 0 g"

            val field = PDTextField(acroForm)
            field.partialName = "signature"
            field.defaultAppearance = "/Helv 10 Tf 0 g"
            val widget = field.widgets[0]
            widget.rectangle = fieldRect
            widget.page = page
            page.annotations.add(widget)
            acroForm.fields = listOf(field)

            return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    private fun signaturePng(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color.BLACK
        g.drawLine(0, height / 2, width - 1, height / 2)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private data class Geometry(val width: Float, val height: Float, val x: Float, val y: Float)

    /**
     * Extracts the geometry of the LAST image drawn on the first page: drawImage emits
     * `q; [w 0 0 h x y] cm; /Name Do; Q`, so the matrix preceding an image-XObject `Do`
     * carries the drawn size and position.
     */
    private fun drawnImageGeometry(pdfBytes: ByteArray): Geometry {
        Loader.loadPDF(pdfBytes).use { doc ->
            val page = doc.getPage(0)
            val imageNames = page.resources.xObjectNames
                .asSequence().filter { page.resources.isImageXObject(it) }.map { it.name }.toSet()
            assertTrue(imageNames.isNotEmpty(), "Page must contain an image XObject")

            val parser = PDFStreamParser(page)
            var lastCm: List<Float>? = null
            var geometry: Geometry? = null
            var pendingCm: List<Float>? = null
            val operands = mutableListOf<Any>()
            while (true) {
                val token = parser.parseNextToken() ?: break
                if (token is Operator) {
                    when (token.name) {
                        "cm" -> {
                            lastCm = operands.filterIsInstance<COSNumber>().map { it.floatValue() }
                            pendingCm = lastCm
                        }
                        "Do" -> {
                            val name = (operands.lastOrNull() as? COSName)?.name
                            if (name in imageNames && pendingCm != null && pendingCm.size == 6) {
                                geometry = Geometry(pendingCm[0], pendingCm[3], pendingCm[4], pendingCm[5])
                            }
                        }
                    }
                    operands.clear()
                } else {
                    operands.add(token)
                }
            }
            return geometry ?: error("No image draw operation found in page content stream")
        }
    }
}
