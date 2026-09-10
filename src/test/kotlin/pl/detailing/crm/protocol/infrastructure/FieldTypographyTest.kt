package pl.detailing.crm.protocol.infrastructure

import io.mockk.mockk
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.interactive.form.PDVariableText
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.S3Client

/**
 * Wpisy w polach mają wyglądać jak część dokumentu: ten sam krój co szablon (Inter),
 * rozmiar zbliżony do etykiet, kolor tuszu. Wartość, która się nie mieści, schodzi
 * w dół do rozmiaru, przy którym wchodzi w pole, zamiast być obcinana.
 */
class FieldTypographyTest {

    private val service = PdfProcessingService(mockk<S3Client>(), "test-bucket")

    private fun bundled(): ByteArray =
        javaClass.getResourceAsStream("/templates/protokol_przyjecia_pojazdu_default.pdf")!!.use { it.readBytes() }

    @Test
    fun `domyslna typografia to Inter 8,5 pt w kolorze tuszu dokumentu`() {
        assertEquals("/fonts/Inter-Regular.ttf", FieldTypography.DEFAULT.fontResource)
        assertEquals(8.5f, FieldTypography.DEFAULT.fontSize)
        assertEquals("0.031 0.024 0.024 rg", FieldTypography.DEFAULT.colorOperator)
    }

    @Test
    fun `wypelniony protokol osadza Inter i zachowuje polskie znaki`() {
        val filled = service.fillFormInMemory(
            bundled(),
            mapOf("fullname" to "Małgorzata Wiśniewska-Zając", "brand" to "Škoda")
        )

        Loader.loadPDF(filled).use { doc ->
            val fontNames = doc.getPage(0).resources.fontNames
                .mapNotNull { doc.getPage(0).resources.getFont(it)?.name }
            assertTrue(fontNames.any { "Inter" in it }, "brak Inter wśród fontów strony: $fontNames")
            val text = PDFTextStripper().getText(doc)
            assertTrue("Małgorzata Wiśniewska-Zając" in text, text)
            assertTrue("Škoda" in text, text)
        }
    }

    @Test
    fun `dluga nazwa uslugodawcy schodzi z rozmiarem, az zmiesci sie w polu`() {
        Loader.loadPDF(bundled()).use { doc ->
            val font = javaClass.getResourceAsStream("/fonts/Inter-Regular.ttf")!!.use { PDType0Font.load(doc, it, false) }
            val provider = doc.documentCatalog.acroForm.getField("provider") as PDVariableText

            val short = service.fittingFontSize(provider, "Bellissimoto", font, 8.5f)
            val twoLines = service.fittingFontSize(provider, "Bellissimoto Detailing, ul. Puławska 145, 02-715 Warszawa", font, 8.5f)
            val fourLines = service.fittingFontSize(
                provider,
                "Bellissimoto Detailing i Ochrona Lakieru Spółka z ograniczoną odpowiedzialnością, ul. Marszałkowska 142/17, 00-061 Warszawa",
                font, 8.5f
            )

            assertEquals(8.5f, short)
            // 57 znaków w polu 126 pt to w 8,5 pt trzy linie (31 pt > 26 pt), w 7,5 pt dwie.
            assertEquals(7.5f, twoLines)
            assertTrue(fourLines < twoLines, "dłuższy tekst musi zejść z rozmiarem: $fourLines")
            assertTrue(fourLines >= 6f, "nie schodzimy poniżej progu czytelności: $fourLines")
        }
    }

    @Test
    fun `jednoliniowe pole uslugodawcy w protokole wydania lamie adres zamiast go obcinac`() {
        val template = javaClass.getResourceAsStream("/templates/protokol_wydania_pojazdu_default.pdf")!!.use { it.readBytes() }
        val provider = "Bellissimoto Detailing, ul. Puławska 145, 02-715 Warszawa"

        Loader.loadPDF(template).use { doc ->
            val field = doc.documentCatalog.acroForm.getField("provider") as org.apache.pdfbox.pdmodel.interactive.form.PDTextField
            assertTrue(!field.isMultiline, "szablon wydania ma jednoliniowe pole usługodawcy")
            val font = javaClass.getResourceAsStream("/fonts/Inter-Regular.ttf")!!.use { PDType0Font.load(doc, it, false) }

            val size = service.fittingFontSize(field, provider, font, 8.5f)

            assertTrue(field.isMultiline, "pole zostało przełączone na wieloliniowe")
            assertTrue(size >= 7.5f, "po złamaniu tekst nie musi schodzić do nieczytelności: $size")
        }

        val filled = service.fillFormInMemory(template, mapOf("provider" to provider))
        Loader.loadPDF(filled).use { doc ->
            val text = PDFTextStripper().getText(doc)
            assertTrue("Warszawa" in text, "koniec adresu ma być w dokumencie: $text")
        }
    }

    @Test
    fun `dawna typografia nadal dziala`() {
        val filled = service.fillFormInMemory(bundled(), mapOf("brand" to "Porsche"), typography = FieldTypography.LEGACY)
        Loader.loadPDF(filled).use { doc ->
            assertTrue("Porsche" in PDFTextStripper().getText(doc))
        }
    }
}
