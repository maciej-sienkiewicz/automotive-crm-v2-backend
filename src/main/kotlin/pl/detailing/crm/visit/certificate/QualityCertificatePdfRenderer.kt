package pl.detailing.crm.visit.certificate

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.awt.Color
import java.io.ByteArrayOutputStream

/**
 * Certyfikat jakości — rysowany od zera PDFBoxem, w tym samym systemie wizualnym co
 * domyślne szablony dokumentów (`resources/templates/protokol_*.html`).
 *
 * Dlaczego nie szablon w „Dokumentach i podpisach": certyfikat nie jest dokumentem do
 * podpisu klienta i nie przewidujemy podmiany jego wyglądu — poszedłby przez maszynerię
 * mapowań pól i weryfikacji szablonu, która na jednej ustalonej formie tylko przeszkadza.
 * Dlatego układ jest w kodzie, a nie w bazie.
 *
 * Dlaczego PDFBox, a nie HTML: repozytorium nie ma silnika HTML→PDF (i nie dokładamy go
 * dla jednego dokumentu), za to ma PDFBoxa, fonty w `resources/fonts` i gotowy wzorzec
 * rysowania strony od zera — [pl.detailing.crm.signing.infrastructure.AuditTrailPageGenerator].
 *
 * Siatka, barwy i rozmiary są przepisane z szablonów HTML co do punktu: granatowe belki
 * 13,92 pt (#111729), szare pola (#EDEEEE), strona A4 595,44 × 841,92 pt, marginesy
 * 30,24 pt / 29,76 pt, tytuł 15 pt z akcentem dobiegającym do krawędzi strony.
 *
 * Na certyfikacie NIE MA kwot — jest podziękowaniem, nie rozliczeniem. To świadome:
 * dokument, który nie niesie ceny, nie ma jak jej przekłamać (CLAUDE.md §1).
 */
@Service
class QualityCertificatePdfRenderer {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        // ── Siatka strony (1:1 z szablonów HTML) ──
        private const val PAGE_W = 595.44f
        private const val PAGE_H = 841.92f
        private const val LEFT = 30.24f
        private const val RIGHT_MARGIN = 29.76f
        private const val TOP = 28.00f
        private const val BOTTOM = 34.00f
        private val CONTENT_W = PAGE_W - LEFT - RIGHT_MARGIN

        // ── Barwy ──
        private val NAVY = Color(0x11, 0x17, 0x29)
        private val GRAY = Color(0xED, 0xEE, 0xEE)
        private val INK = Color(0x08, 0x06, 0x06)
        private val MUTED = Color(0x6B, 0x72, 0x80)

        // ── Typografia ──
        private const val TAB_H = 13.92f
        private const val TAB_FONT = 9f
        private const val TAB_PAD = 9f
        private const val TITLE_FONT = 15f
        private const val META_LABEL = 7f
        private const val BODY = 9f
        private const val BODY_LEAD = 12.5f
        private const val LEAD_FONT = 8.5f
        private const val LEAD_LEAD = 11.5f
        private const val NOTE_FONT = 8f
        private const val NOTE_LEAD = 10f

        /** Wysokość slotu na logo w nagłówku — jak `.company-logo` w szablonach. */
        private const val LOGO_W = 188.16f
        private const val LOGO_H = 36f
    }

    /**
     * Kursor rysowania po dokumencie: trzyma bieżącą stronę, jej strumień i pozycję Y.
     *
     * Istnieje, bo certyfikat ma zmienną długość — trzy listy, każda może mieć jedną
     * pozycję albo dwadzieścia. Bez łamania stron długa lista po prostu wyjeżdżałaby
     * poza kartkę i nikt by tego nie zobaczył aż u klienta.
     */
    private class Sheet(val doc: PDDocument) {
        var page: PDPage = PDPage(PDRectangle(PAGE_W, PAGE_H))
        var cs: PDPageContentStream
        var y: Float = PAGE_H - TOP

        init {
            doc.addPage(page)
            cs = PDPageContentStream(doc, page)
        }

        /** Przełamuje stronę, gdy żądany blok nie mieści się już w całości. */
        fun ensure(space: Float) {
            if (y - space >= BOTTOM) return
            cs.close()
            page = PDPage(PDRectangle(PAGE_W, PAGE_H))
            doc.addPage(page)
            cs = PDPageContentStream(doc, page)
            y = PAGE_H - TOP
        }

        fun close() = cs.close()
    }

    fun render(data: QualityCertificateData): ByteArray = PDDocument().use { doc ->
        // Liberation Sans, bo dokładnie tym fontem backend wypełnia pola w protokołach
        // (PdfProcessingService) — certyfikat ma być z tej samej rodziny, a nie „prawie".
        val regular = requireNotNull(loadFont(doc, "/fonts/LiberationSans-Regular.ttf")) {
            "Brak fontu /fonts/LiberationSans-Regular.ttf w classpath"
        }
        val bold = loadFont(doc, "/fonts/LiberationSans-Bold.ttf") ?: regular

        val sheet = Sheet(doc)

        drawHeader(sheet, doc, regular, bold, data)
        drawTitle(sheet, bold)
        drawMetaRow(sheet, regular, bold, data)
        drawLead(sheet, regular, data)

        drawList(
            sheet, regular, bold,
            label = "ZAKRES WYKONANYCH USŁUG",
            items = data.services.map { ListEntry(it, null) },
            emptyText = "Nie wskazano usług do umieszczenia na certyfikacie."
        )
        drawList(
            sheet, regular, bold,
            label = "UŻYTE PRODUKTY",
            items = data.usedProducts.map { ListEntry(it.title, it.note) },
            emptyText = "Nie wskazano produktów do umieszczenia na certyfikacie."
        )
        drawList(
            sheet, regular, bold,
            label = "ZALECANE DO DALSZEJ PIELĘGNACJI",
            items = data.recommendedProducts.map { ListEntry(it.title, it.note) },
            emptyText = null
        )

        drawSignature(sheet, doc, regular, data)
        sheet.close()

        ByteArrayOutputStream().use { out ->
            doc.save(out)
            out.toByteArray()
        }
    }

    // ── Nagłówek: logo studia po lewej, usługodawca po prawej ──

    private fun drawHeader(
        sheet: Sheet,
        doc: PDDocument,
        regular: PDFont,
        bold: PDFont,
        data: QualityCertificateData
    ) {
        val top = sheet.y
        val logoTop = top - 4f

        data.logoPng?.let { png ->
            runCatching {
                val image = PDImageXObject.createFromByteArray(doc, png, "studio-logo")
                val scale = minOf(LOGO_W / image.width, LOGO_H / image.height)
                val w = image.width * scale
                val h = image.height * scale
                // Wyrównanie do lewej i wyśrodkowanie w pionie slotu — jak w szablonach
                // (`object-position: left center`).
                sheet.cs.drawImage(image, LEFT - 0.94f, logoTop - LOGO_H + (LOGO_H - h) / 2f, w, h)
            }.onFailure { logger.warn("Nie udało się wstawić logo na certyfikat: ${it.message}") }
        }

        val boxW = 180f
        val boxX = PAGE_W - RIGHT_MARGIN - boxW
        drawTab(sheet.cs, regular, boxX, logoTop, "USŁUGODAWCA", boxW)
        val boxTop = logoTop - TAB_H - 2.28f
        val boxH = 30.13f
        fillRect(sheet.cs, boxX, boxTop - boxH, boxW, boxH, GRAY)
        val providerLines = wrap(data.providerName, bold, META_LABEL, boxW - 4f).take(3)
        var ty = boxTop - 10f
        providerLines.forEach { line ->
            text(sheet.cs, bold, META_LABEL, boxX + 2f, ty, line, Color.BLACK)
            ty -= 9f
        }

        sheet.y = top - 68.65f
    }

    private fun drawTitle(sheet: Sheet, bold: PDFont) {
        sheet.y -= 9.39f
        val top = sheet.y
        // Akcent zaczyna się od krawędzi strony, tak jak `.title-row .accent`.
        fillRect(sheet.cs, 0f, top - TAB_H, 30.48f, TAB_H, NAVY)
        text(sheet.cs, bold, TITLE_FONT, 30.48f + 5.52f, top - TAB_H + 2.5f, "CERTYFIKAT JAKOŚCI", NAVY)
        sheet.y = top - 18.15f
    }

    private fun drawMetaRow(sheet: Sheet, regular: PDFont, bold: PDFont, data: QualityCertificateData) {
        sheet.y -= 23.57f
        val top = sheet.y
        val gap = 8f
        val colW = (CONTENT_W - gap * 3) / 4f
        val columns = listOf(
            "NR WIZYTY" to data.visitNumber,
            "POJAZD" to data.vehicle,
            "DATA WYDANIA" to data.completedOn,
            "KLIENT" to data.customerName
        )
        columns.forEachIndexed { index, (label, value) ->
            val x = LEFT + index * (colW + gap)
            drawTab(sheet.cs, regular, x, top, label, colW)
            val boxTop = top - TAB_H - 2.55f
            val boxH = 18.42f
            fillRect(sheet.cs, x, boxTop - boxH, colW, boxH, GRAY)
            val shown = ellipsize(value, bold, META_LABEL, colW - 4f)
            text(sheet.cs, bold, META_LABEL, x + 2f, boxTop - 12f, shown, Color.BLACK)
        }
        sheet.y = top - TAB_H - 2.55f - 18.42f
    }

    private fun drawLead(sheet: Sheet, regular: PDFont, data: QualityCertificateData) {
        sheet.y -= 18f
        data.thankYou.forEachIndexed { index, paragraph ->
            if (index > 0) sheet.y -= 5f
            wrap(paragraph, regular, LEAD_FONT, CONTENT_W).forEach { line ->
                sheet.ensure(LEAD_LEAD)
                text(sheet.cs, regular, LEAD_FONT, LEFT, sheet.y - LEAD_FONT, line, INK)
                sheet.y -= LEAD_LEAD
            }
        }
    }

    private class ListEntry(val title: String, val note: String?)

    /**
     * Sekcja listowa: granatowa belka, pod nią pozycje z kropką i opcjonalną notatką.
     *
     * `emptyText == null` oznacza sekcję, która przy pustej liście w ogóle się nie rysuje —
     * tak jest z zaleceniami, bo pusta rubryka „polecamy" wygląda jak niedokończony dokument.
     */
    private fun drawList(
        sheet: Sheet,
        regular: PDFont,
        bold: PDFont,
        label: String,
        items: List<ListEntry>,
        emptyText: String?
    ) {
        if (items.isEmpty() && emptyText == null) return

        sheet.ensure(TAB_H + 24f)
        sheet.y -= 15.85f
        drawTab(sheet.cs, regular, LEFT, sheet.y, label, null)
        sheet.y -= TAB_H + 8f

        if (items.isEmpty()) {
            sheet.ensure(BODY_LEAD)
            text(sheet.cs, regular, NOTE_FONT, LEFT, sheet.y - NOTE_FONT, emptyText!!, MUTED)
            sheet.y -= NOTE_LEAD
            return
        }

        val bulletIndent = 12f
        items.forEach { item ->
            val titleLines = wrap(item.title, bold, BODY, CONTENT_W - bulletIndent)
            titleLines.forEachIndexed { index, line ->
                sheet.ensure(BODY_LEAD)
                if (index == 0) {
                    // Kropka listy rysowana jako mały kwadrat w kolorze marki dokumentu —
                    // znak „•" bywa nieobecny w subsecie fontu, kwadrat nigdy nie zawiedzie.
                    fillRect(sheet.cs, LEFT + 1.5f, sheet.y - BODY + 1.5f, 3f, 3f, NAVY)
                }
                text(sheet.cs, bold, BODY, LEFT + bulletIndent, sheet.y - BODY, line, INK)
                sheet.y -= BODY_LEAD
            }
            item.note?.takeIf { it.isNotBlank() }?.let { note ->
                wrap(note, regular, NOTE_FONT, CONTENT_W - bulletIndent).forEach { line ->
                    sheet.ensure(NOTE_LEAD)
                    text(sheet.cs, regular, NOTE_FONT, LEFT + bulletIndent, sheet.y - NOTE_FONT, line, MUTED)
                    sheet.y -= NOTE_LEAD
                }
            }
            sheet.y -= 3f
        }
    }

    /**
     * Podpis wykonawcy: obraz podpisu zalogowanego użytkownika w szarym polu, pod nim
     * imię i nazwisko oraz data wystawienia.
     *
     * Blok jest niepodzielny — gdy nie mieści się na stronie, idzie w całości na następną.
     * Podpis oderwany od nazwiska na osobnej kartce nie jest podpisem.
     */
    private fun drawSignature(sheet: Sheet, doc: PDDocument, regular: PDFont, data: QualityCertificateData) {
        val boxW = 200f
        val boxH = 48f
        val blockH = TAB_H + 2.30f + boxH + 20f
        sheet.ensure(blockH + 22f)
        // Podpis jest stopką dokumentu, nie kolejnym akapitem: gdy na stronie zostało
        // miejsce, blok zjeżdża na dół kartki — tak samo jak w protokołach. Bez tego
        // krótki certyfikat ma podpis w połowie strony i pustkę pod nim.
        sheet.y = minOf(sheet.y - 22f, BOTTOM + blockH)

        val x = PAGE_W - RIGHT_MARGIN - boxW
        drawTab(sheet.cs, regular, x, sheet.y, "PODPIS WYKONAWCY", boxW)
        val boxTop = sheet.y - TAB_H - 2.30f
        fillRect(sheet.cs, x, boxTop - boxH, boxW, boxH, GRAY)

        data.signaturePng?.let { png ->
            runCatching {
                val image = PDImageXObject.createFromByteArray(doc, png, "user-signature")
                val pad = 6f
                val scale = minOf((boxW - 2 * pad) / image.width, (boxH - 2 * pad) / image.height)
                val w = image.width * scale
                val h = image.height * scale
                sheet.cs.drawImage(image, x + (boxW - w) / 2f, boxTop - boxH + (boxH - h) / 2f, w, h)
            }.onFailure { logger.warn("Nie udało się wstawić podpisu na certyfikat: ${it.message}") }
        }

        val captionY = boxTop - boxH - 10f
        val caption = listOf(data.issuedByName, data.issuedOn).filter { it.isNotBlank() }.joinToString(" · ")
        text(sheet.cs, regular, NOTE_FONT, x, captionY, ellipsize(caption, regular, NOTE_FONT, boxW), MUTED)
        sheet.y = captionY - 6f
    }

    // ── Prymitywy rysowania ──

    /** Granatowa belka z białym napisem. `width == null` = szerokość dopasowana do tekstu. */
    private fun drawTab(cs: PDPageContentStream, font: PDFont, x: Float, top: Float, label: String, width: Float?) {
        val safe = sanitize(label)
        val textW = stringWidth(font, TAB_FONT, safe)
        val w = width ?: (textW + 2 * TAB_PAD)
        fillRect(cs, x, top - TAB_H, w, TAB_H, NAVY)
        val tx = x + maxOf(TAB_PAD, (w - textW) / 2f)
        text(cs, font, TAB_FONT, tx, top - TAB_H + 4.2f, safe, Color.WHITE)
    }

    private fun fillRect(cs: PDPageContentStream, x: Float, y: Float, w: Float, h: Float, color: Color) {
        cs.setNonStrokingColor(color)
        cs.addRect(x, y, w, h)
        cs.fill()
    }

    private fun text(cs: PDPageContentStream, font: PDFont, size: Float, x: Float, y: Float, value: String, color: Color) {
        val safe = encodable(font, sanitize(value).replace("\n", " "))
        if (safe.isEmpty()) return
        cs.beginText()
        cs.setNonStrokingColor(color)
        cs.setFont(font, size)
        cs.newLineAtOffset(x, y)
        cs.showText(safe)
        cs.endText()
    }

    private fun stringWidth(font: PDFont, size: Float, value: String): Float =
        runCatching { font.getStringWidth(encodable(font, value)) / 1000f * size }
            .getOrDefault(value.length * size * 0.5f)

    /**
     * Wycina znaki, dla których osadzony font nie ma glifu.
     *
     * PDFBox nie pomija ich po cichu — rzuca wyjątkiem dopiero przy zapisie, czyli gdy
     * dokument jest już w połowie narysowany. Jedna cyrylica albo emoji w nazwie produktu
     * z sieci wywaliłoby całe generowanie, więc filtrujemy z góry.
     */
    private fun encodable(font: PDFont, value: String): String {
        if (value.isEmpty()) return value
        if (runCatching { font.getStringWidth(value) }.isSuccess) return value
        return value.filter { ch -> runCatching { font.getStringWidth(ch.toString()) }.isSuccess }
    }

    private fun wrap(value: String, font: PDFont, size: Float, maxWidth: Float): List<String> {
        val safe = sanitize(value).trim()
        if (safe.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        safe.split("\n").forEach { paragraph ->
            var current = StringBuilder()
            paragraph.split(" ").filter { it.isNotEmpty() }.forEach { word ->
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (stringWidth(font, size, candidate) <= maxWidth) {
                    current = StringBuilder(candidate)
                } else {
                    if (current.isNotEmpty()) lines += current.toString()
                    // Pojedyncze słowo dłuższe niż wiersz (np. wklejony adres) łamiemy
                    // po znakach — inaczej wyjechałoby poza margines.
                    current = StringBuilder()
                    var chunk = StringBuilder()
                    word.forEach { ch ->
                        if (stringWidth(font, size, "$chunk$ch") <= maxWidth) {
                            chunk.append(ch)
                        } else {
                            lines += chunk.toString()
                            chunk = StringBuilder(ch.toString())
                        }
                    }
                    current = chunk
                }
            }
            if (current.isNotEmpty()) lines += current.toString()
        }
        return lines
    }

    private fun ellipsize(value: String, font: PDFont, size: Float, maxWidth: Float): String {
        val safe = sanitize(value)
        if (stringWidth(font, size, safe) <= maxWidth) return safe
        var cut = safe
        while (cut.isNotEmpty() && stringWidth(font, size, "$cut...") > maxWidth) {
            cut = cut.dropLast(1)
        }
        return "$cut..."
    }

    /**
     * Porządkuje białe znaki. Twarda spacja i tabulator psują łamanie wierszy — liczą się
     * jako zwykły znak, a nie jako miejsce podziału — więc idą na zwykłą spację.
     * Typografii (myślników, cudzysłowów, wielokropka) NIE ruszamy: Liberation Sans je zna,
     * a tego, czego nie zna, pozbywa się [encodable].
     */
    private fun sanitize(value: String): String {
        val normalized = value
            .replace('\u00A0', ' ')
            .replace("\t", " ")
        return buildString {
            normalized.forEach { ch ->
                when {
                    ch == '\n' -> append(ch)
                    ch.code < 32 -> append(' ')
                    else -> append(ch)
                }
            }
        }
    }

    private fun loadFont(doc: PDDocument, path: String): PDFont? = runCatching {
        javaClass.getResourceAsStream(path)?.use { PDType0Font.load(doc, it) }
    }.onFailure { logger.warn("Nie udało się wczytać fontu $path: ${it.message}") }.getOrNull()
}
