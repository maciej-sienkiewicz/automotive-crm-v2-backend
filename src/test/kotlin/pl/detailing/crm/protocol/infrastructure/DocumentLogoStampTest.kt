package pl.detailing.crm.protocol.infrastructure

import io.mockk.mockk
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.studio.logo.LogoTestImages
import software.amazon.awssdk.services.s3.S3Client

/**
 * Logo studia trafia na pierwszą stronę bundlowanych szablonów systemowych jako
 * obraz w treści strony (nie jako pole formularza), więc przeżywa spłaszczenie,
 * podpis i pieczęć bez dalszej obsługi. Bez logo dokument wygląda jak dotąd —
 * szablon systemowy sam nie niesie żadnej marki.
 */
class DocumentLogoStampTest {

    private val service = PdfProcessingService(mockk<S3Client>(), "test-bucket")

    private fun bundled(resource: String): ByteArray =
        javaClass.getResourceAsStream(resource)!!.use { it.readBytes() }

    private fun imageXObjectsOnFirstPage(pdf: ByteArray): Int =
        Loader.loadPDF(pdf).use { doc: PDDocument ->
            val resources = doc.getPage(0).resources
            resources.xObjectNames.count { resources.isImageXObject(it) }
        }

    @Test
    fun `stempluje logo na pierwszej stronie protokolu przyjecia i zgody`() {
        val logo = LogoTestImages.transparentPngWithBox(1200, 300, 0)

        listOf(
            "/templates/protokol_przyjecia_pojazdu_default.pdf",
            "/templates/protokol_wydania_pojazdu_default.pdf",
            "/templates/zgody_marketingowe_default.pdf"
        ).forEach { template ->
            val bytes = bundled(template)
            val without = imageXObjectsOnFirstPage(service.fillFormInMemory(bytes, emptyMap()))
            val with = imageXObjectsOnFirstPage(service.fillFormInMemory(bytes, emptyMap(), logoPng = logo))
            assertEquals(without + 1, with, "brak stempla logo w $template")
        }
    }

    @Test
    fun `pole podpisu przezywa stemplowanie logo`() {
        val filled = service.fillFormInMemory(
            bundled("/templates/protokol_przyjecia_pojazdu_default.pdf"),
            mapOf("brand" to "Porsche"),
            logoPng = LogoTestImages.transparentPngWithBox(600, 600, 0)
        )
        Loader.loadPDF(filled).use { doc ->
            val acroForm = doc.documentCatalog.acroForm
            assertNotNull(acroForm)
            assertNotNull(acroForm.getField(PdfProcessingService.SIGNATURE_FIELD_NAME))
            assertTrue(doc.numberOfPages >= 1)
        }
    }

    @Test
    fun `uszkodzone logo nie blokuje dokumentu`() {
        val filled = service.fillFormInMemory(
            bundled("/templates/protokol_przyjecia_pojazdu_default.pdf"),
            emptyMap(),
            logoPng = "not a png".toByteArray()
        )
        assertTrue(filled.isNotEmpty())
        assertEquals(0, imageXObjectsOnFirstPage(filled))
    }
}
