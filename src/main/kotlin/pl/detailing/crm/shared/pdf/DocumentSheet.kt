package pl.detailing.crm.shared.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.slf4j.LoggerFactory
import java.awt.Color
import java.io.ByteArrayOutputStream

/**
 * Wspólny system wizualny dokumentów drukowanych przez CRM.
 *
 * Siatka, barwy i rozmiary są przepisane co do punktu z domyślnych szablonów
 * (`resources/templates/protokol_*.html`): granatowe belki 13,92 pt, szare pola,
 * A4 595,44 × 841,92 pt, marginesy 30,24 / 29,76 pt, tytuł 15 pt z akcentem
 * dobiegającym do krawędzi kartki.
 *
 * Stała jest tu, a nie w generatorze, bo dokumenty mają wyglądać jak jedna rodzina
 * PRZEZ KONSTRUKCJĘ. Certyfikat jakości i wizualizacja faktury różnią się treścią,
 * nie krojem belki; skopiowany układ rozjeżdża się przy pierwszej poprawce w jednym
 * z nich.
 */
object DocumentStyle {
    const val PAGE_W = 595.44f
    const val PAGE_H = 841.92f
    const val LEFT = 30.24f
    const val RIGHT_MARGIN = 29.76f
    const val TOP = 28.00f
    const val BOTTOM = 34.00f
    const val CONTENT_W = PAGE_W - LEFT - RIGHT_MARGIN

    val NAVY: Color = Color(0x11, 0x17, 0x29)
    val GRAY: Color = Color(0xED, 0xEE, 0xEE)
    val INK: Color = Color(0x08, 0x06, 0x06)
    val MUTED: Color = Color(0x6B, 0x72, 0x80)

    const val TAB_H = 13.92f
    const val TAB_FONT = 9f
    const val TAB_PAD = 9f
    const val TITLE_FONT = 15f
    const val META_FONT = 7f
    const val BODY = 9f
    const val BODY_LEAD = 12.5f
    const val LEAD_FONT = 8.5f
    const val LEAD_LEAD = 11.5f
    const val NOTE_FONT = 8f
    const val NOTE_LEAD = 10f

    /** Slot na logo studia w nagłówku — jak `.company-logo` w szablonach HTML. */
    const val LOGO_W = 188.16f
    const val LOGO_H = 36f

    /** Wysokość bloku nagłówka: logo po lewej, pole „usługodawca" po prawej. */
    const val HEADER_H = 68.65f
}

/**
 * Kursor rysowania po dokumencie: trzyma bieżącą stronę, jej strumień i pozycję Y,
 * i udostępnia prymitywy wspólnego systemu wizualnego.
 *
 * Istnieje, bo dokumenty CRM mają zmienną długość — lista pozycji faktury czy prac na
 * certyfikacie może mieć jedną pozycję albo czterdzieści. Bez łamania stron długa lista
 * po prostu wyjeżdża poza kartkę i nikt tego nie zobaczy aż u klienta.
 */
class DocumentSheet(val doc: PDDocument) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Liberation Sans, bo dokładnie tym fontem backend wypełnia pola w protokołach
     * (PdfProcessingService) — dokumenty mają być z jednej rodziny, a nie „prawie".
     */
    val regular: PDFont = requireNotNull(loadFont("/fonts/LiberationSans-Regular.ttf")) {
        "Brak fontu /fonts/LiberationSans-Regular.ttf w classpath"
    }
    val bold: PDFont = loadFont("/fonts/LiberationSans-Bold.ttf") ?: regular

    var page: PDPage = PDPage(PDRectangle(DocumentStyle.PAGE_W, DocumentStyle.PAGE_H))
        private set
    var cs: PDPageContentStream
        private set
    var y: Float = DocumentStyle.PAGE_H - DocumentStyle.TOP

    init {
        doc.addPage(page)
        cs = PDPageContentStream(doc, page)
    }

    /** Przełamuje stronę, gdy żądany blok nie mieści się już w całości. */
    fun ensure(space: Float) {
        if (y - space >= DocumentStyle.BOTTOM) return
        newPage()
    }

    fun newPage() {
        cs.close()
        page = PDPage(PDRectangle(DocumentStyle.PAGE_W, DocumentStyle.PAGE_H))
        doc.addPage(page)
        cs = PDPageContentStream(doc, page)
        y = DocumentStyle.PAGE_H - DocumentStyle.TOP
    }

    override fun close() = cs.close()

    /**
     * Domyka strumień ostatniej strony i zwraca gotowy dokument.
     *
     * Zapis MUSI iść po zamknięciu strumienia — PDFBox nie domyka go sam, a dokument
     * zapisany z otwartym strumieniem gubi ostatnią stronę.
     */
    fun finish(): ByteArray {
        close()
        return ByteArrayOutputStream().use { out ->
            doc.save(out)
            out.toByteArray()
        }
    }

    // ── Prymitywy ─────────────────────────────────────────────────────────────

    /** Granatowa belka z białym napisem. `width == null` = szerokość dopasowana do tekstu. */
    fun tab(x: Float, top: Float, label: String, width: Float? = null): Float {
        val safe = sanitize(label)
        val textW = widthOf(regular, DocumentStyle.TAB_FONT, safe)
        val w = width ?: (textW + 2 * DocumentStyle.TAB_PAD)
        rect(x, top - DocumentStyle.TAB_H, w, DocumentStyle.TAB_H, DocumentStyle.NAVY)
        val tx = x + maxOf(DocumentStyle.TAB_PAD, (w - textW) / 2f)
        text(regular, DocumentStyle.TAB_FONT, tx, top - DocumentStyle.TAB_H + 4.2f, safe, Color.WHITE)
        return w
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Color) {
        cs.setNonStrokingColor(color)
        cs.addRect(x, y, w, h)
        cs.fill()
    }

    fun text(font: PDFont, size: Float, x: Float, y: Float, value: String, color: Color) {
        val safe = encodable(font, sanitize(value).replace("\n", " "))
        if (safe.isEmpty()) return
        cs.beginText()
        cs.setNonStrokingColor(color)
        cs.setFont(font, size)
        cs.newLineAtOffset(x, y)
        cs.showText(safe)
        cs.endText()
    }

    /** Tekst wyrównany do PRAWEJ krawędzi `right` — kolumny kwot na fakturze. */
    fun textRight(font: PDFont, size: Float, right: Float, y: Float, value: String, color: Color) {
        text(font, size, right - widthOf(font, size, value), y, value, color)
    }

    fun widthOf(font: PDFont, size: Float, value: String): Float =
        runCatching { font.getStringWidth(encodable(font, sanitize(value))) / 1000f * size }
            .getOrDefault(value.length * size * 0.5f)

    fun wrap(value: String, font: PDFont, size: Float, maxWidth: Float): List<String> {
        val safe = sanitize(value).trim()
        if (safe.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        safe.split("\n").forEach { paragraph ->
            var current = StringBuilder()
            paragraph.split(" ").filter { it.isNotEmpty() }.forEach { word ->
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (widthOf(font, size, candidate) <= maxWidth) {
                    current = StringBuilder(candidate)
                } else {
                    if (current.isNotEmpty()) lines += current.toString()
                    // Pojedyncze słowo dłuższe niż wiersz (wklejony adres, numer KSeF)
                    // łamiemy po znakach — inaczej wyjechałoby poza margines.
                    var chunk = StringBuilder()
                    word.forEach { ch ->
                        if (widthOf(font, size, "$chunk$ch") <= maxWidth) {
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

    fun ellipsize(value: String, font: PDFont, size: Float, maxWidth: Float): String {
        val safe = sanitize(value)
        if (widthOf(font, size, safe) <= maxWidth) return safe
        var cut = safe
        while (cut.isNotEmpty() && widthOf(font, size, "$cut...") > maxWidth) {
            cut = cut.dropLast(1)
        }
        return "$cut..."
    }

    /** Obraz osadzony w dokumencie albo null, gdy bajty nie dają się odczytać. */
    fun image(bytes: ByteArray, name: String): PDImageXObject? = runCatching {
        PDImageXObject.createFromByteArray(doc, bytes, name)
    }.onFailure { logger.warn("Nie udało się osadzić obrazu $name: ${it.message}") }.getOrNull()

    /**
     * Rysuje obraz wpisany w prostokąt z zachowaniem proporcji.
     *
     * @param alignLeft true = do lewej krawędzi slotu (logo), false = wyśrodkowany (podpis, kod QR)
     */
    fun imageFitted(
        bytes: ByteArray,
        name: String,
        x: Float,
        bottom: Float,
        w: Float,
        h: Float,
        alignLeft: Boolean
    ): Boolean {
        val img = image(bytes, name) ?: return false
        val scale = minOf(w / img.width, h / img.height)
        val iw = img.width * scale
        val ih = img.height * scale
        val ix = if (alignLeft) x else x + (w - iw) / 2f
        return runCatching { cs.drawImage(img, ix, bottom + (h - ih) / 2f, iw, ih); true }
            .onFailure { logger.warn("Nie udało się narysować obrazu $name: ${it.message}") }
            .getOrDefault(false)
    }

    // ── Bloki wspólne dla wszystkich dokumentów ───────────────────────────────

    /**
     * Nagłówek: logo studia po lewej, pole „USŁUGODAWCA" z nazwą i adresem po prawej.
     *
     * Bez logo slot zostawał pusty, a nazwa siedziała drobnym drukiem po prawej — górna
     * trzecia część kartki wyglądała na niedokończoną. Nazwa wchodzi wtedy w miejsce
     * logo, jako znak firmowy złożony pismem, i nie dubluje się już nigdzie w nagłówku.
     */
    fun header(logoPng: ByteArray?, providerName: String, providerAddress: String?) {
        val top = y
        val logoTop = top - 4f
        val drewLogo = logoPng?.let { png ->
            imageFitted(
                png, "studio-logo",
                DocumentStyle.LEFT - 0.94f, logoTop - DocumentStyle.LOGO_H,
                DocumentStyle.LOGO_W, DocumentStyle.LOGO_H, alignLeft = true
            )
        } ?: false

        if (drewLogo) {
            val boxW = 180f
            val boxX = DocumentStyle.PAGE_W - DocumentStyle.RIGHT_MARGIN - boxW
            tab(boxX, logoTop, "USŁUGODAWCA", boxW)
            val boxTop = logoTop - DocumentStyle.TAB_H - 2.28f
            val boxH = 30.13f
            rect(boxX, boxTop - boxH, boxW, boxH, DocumentStyle.GRAY)

            // Trzy wiersze to wszystko, co mieści szare pole: długa nazwa spółki zabiera
            // miejsce adresowi, więc skracamy ją do jednego wiersza zamiast wypychać
            // adres poza pole.
            var ty = boxTop - 8.5f
            text(
                bold, DocumentStyle.META_FONT, boxX + 2f, ty,
                ellipsize(providerName, bold, DocumentStyle.META_FONT, boxW - 4f), Color.BLACK
            )
            ty -= 8.5f
            providerAddress?.let { address ->
                wrap(address, regular, DocumentStyle.META_FONT, boxW - 4f).take(2).forEach { line ->
                    text(regular, DocumentStyle.META_FONT, boxX + 2f, ty, line, Color.BLACK)
                    ty -= 8.5f
                }
            }
        } else {
            var ty = logoTop - DocumentStyle.LOGO_H + 12f
            providerAddress?.let { address ->
                text(
                    regular, DocumentStyle.NOTE_FONT, DocumentStyle.LEFT, ty,
                    ellipsize(address, regular, DocumentStyle.NOTE_FONT, DocumentStyle.CONTENT_W),
                    DocumentStyle.MUTED
                )
                ty += 15f
            }
            wrap(providerName, bold, 13f, DocumentStyle.CONTENT_W).take(2).asReversed().forEach { line ->
                text(bold, 13f, DocumentStyle.LEFT, ty, line, DocumentStyle.NAVY)
                ty += 16f
            }
        }

        y = top - DocumentStyle.HEADER_H
    }

    /** Tytuł dokumentu: granatowy akcent od krawędzi kartki i nazwa 15 pt. */
    fun title(label: String) {
        y -= 9.39f
        val top = y
        rect(0f, top - DocumentStyle.TAB_H, 30.48f, DocumentStyle.TAB_H, DocumentStyle.NAVY)
        text(bold, DocumentStyle.TITLE_FONT, 30.48f + 5.52f, top - DocumentStyle.TAB_H + 2.5f, label, DocumentStyle.NAVY)
        y = top - 18.15f
    }

    /** Rząd pól metryczki: belka z podpisem i szare pole z wartością, równe kolumny. */
    fun metaRow(columns: List<Pair<String, String>>, gapAbove: Float = 23.57f) {
        if (columns.isEmpty()) return
        y -= gapAbove
        val top = y
        val gap = 8f
        val colW = (DocumentStyle.CONTENT_W - gap * (columns.size - 1)) / columns.size
        columns.forEachIndexed { index, (label, value) ->
            val x = DocumentStyle.LEFT + index * (colW + gap)
            tab(x, top, label, colW)
            val boxTop = top - DocumentStyle.TAB_H - 2.55f
            val boxH = 18.42f
            rect(x, boxTop - boxH, colW, boxH, DocumentStyle.GRAY)
            text(
                bold, DocumentStyle.META_FONT, x + 2f, boxTop - 12f,
                ellipsize(value, bold, DocumentStyle.META_FONT, colW - 4f), Color.BLACK
            )
        }
        y = top - DocumentStyle.TAB_H - 2.55f - 18.42f
    }

    // ── Higiena tekstu ────────────────────────────────────────────────────────

    /**
     * Porządkuje białe znaki. Twarda spacja i tabulator psują łamanie wierszy — liczą się
     * jako zwykły znak, a nie jako miejsce podziału — więc idą na zwykłą spację.
     */
    private fun sanitize(value: String): String {
        val normalized = value.replace(' ', ' ').replace("\t", " ")
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

    /**
     * Wycina znaki, dla których osadzony font nie ma glifu.
     *
     * PDFBox nie pomija ich po cichu — rzuca wyjątkiem dopiero przy zapisie, czyli gdy
     * dokument jest już w połowie narysowany. Jedna cyrylica w nazwie pozycji faktury
     * wywaliłaby całe generowanie, więc filtrujemy z góry.
     */
    private fun encodable(font: PDFont, value: String): String {
        if (value.isEmpty()) return value
        if (runCatching { font.getStringWidth(value) }.isSuccess) return value
        return value.filter { ch -> runCatching { font.getStringWidth(ch.toString()) }.isSuccess }
    }

    private fun loadFont(path: String): PDFont? = runCatching {
        javaClass.getResourceAsStream(path)?.use { PDType0Font.load(doc, it) }
    }.onFailure { logger.warn("Nie udało się wczytać fontu $path: ${it.message}") }.getOrNull()
}
