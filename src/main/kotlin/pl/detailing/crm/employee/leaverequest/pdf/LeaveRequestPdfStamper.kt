package pl.detailing.crm.employee.leaverequest.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.ValidationException
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Wtapia w wniosek to, co powstaje po jego wygenerowaniu: podpis pracownika z chwilą
 * złożenia (H1 → H2) i decyzję z podpisem rozpatrującego (H2 → H3).
 *
 * Każdy stempel zwraca NOWY plik — wejście zostaje nietknięte, bo to na jego skrót
 * powołuje się podpis. Pola bierze z [LeaveRequestPdfRenderer.layout], więc tekst
 * i podpis trafiają dokładnie w szare pola narysowane przy generowaniu.
 *
 * Obraz podpisu przychodzi już znormalizowany (przezroczyste PNG z samymi pociągnięciami,
 * [pl.detailing.crm.signing.infrastructure.SignatureImageProcessor]) i żyje wyłącznie
 * w pamięci — zapisany zostaje tylko gotowy PDF.
 */
@Service
class LeaveRequestPdfStamper(private val renderer: LeaveRequestPdfRenderer) {

    companion object {
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter
            .ofPattern("dd.MM.yyyy, HH:mm:ss", Locale.forLanguageTag("pl-PL"))
            .withZone(ZoneId.of("Europe/Warsaw"))
    }

    /** Podpis pracownika: chwila złożenia w metryczce, obraz w polu podpisu, data i godzina obok. */
    fun stampEmployeeSignature(pdf: ByteArray, normalizedSignaturePng: ByteArray, signedAt: Instant): ByteArray =
        edit(pdf) { ink ->
            val layout = renderer.layout
            val at = TIMESTAMP.format(signedAt)
            ink.singleLine(layout.submittedAt, at, bold = true)
            ink.signature(layout.employeeSignature, normalizedSignaturePng)
            ink.singleLine(layout.employeeSignedAt, at, bold = true)
        }

    /**
     * Decyzja: zaznaczenie, osoba rozpatrująca, uzasadnienie, podpis i chwila decyzji.
     * [appendPages] dokłada strony na końcu — tam trafia karta podpisów.
     */
    fun stampDecision(
        pdf: ByteArray,
        approved: Boolean,
        decidedByName: String,
        note: String?,
        normalizedSignaturePng: ByteArray,
        decidedAt: Instant,
        appendPages: (PDDocument) -> Unit = {}
    ): ByteArray = edit(pdf, appendPages) { ink ->
        val layout = renderer.layout
        ink.mark(if (approved) layout.decisionApproved else layout.decisionRejected)
        ink.singleLine(layout.decidedBy, decidedByName)
        note?.takeIf { it.isNotBlank() }?.let { ink.multiLine(layout.decisionNote, it) }
        ink.signature(layout.approverSignature, normalizedSignaturePng)
        ink.singleLine(layout.decidedAt, TIMESTAMP.format(decidedAt), bold = true)
    }

    private fun edit(pdf: ByteArray, appendPages: (PDDocument) -> Unit = {}, block: (Ink) -> Unit): ByteArray =
        Loader.loadPDF(pdf).use { document ->
            if (document.numberOfPages == 0) throw ValidationException("Wniosek nie ma żadnej strony")
            val page = document.getPage(0)
            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                block(Ink(document, cs))
            }
            appendPages(document)
            ByteArrayOutputStream().use { out ->
                document.save(out)
                out.toByteArray()
            }
        }

    /** Prymitywy pisania po istniejącej stronie — ta sama typografia co pola w rendererze. */
    private class Ink(private val document: PDDocument, private val cs: PDPageContentStream) {
        private val regular: PDFont = load("/fonts/LiberationSans-Regular.ttf")
        private val bold: PDFont = runCatching { load("/fonts/LiberationSans-Bold.ttf") }.getOrDefault(regular)

        private fun load(path: String): PDFont =
            LeaveRequestPdfStamper::class.java.getResourceAsStream(path)!!.use { PDType0Font.load(document, it, true) }

        fun singleLine(box: PdfBox, value: String, bold: Boolean = false) {
            val font = if (bold) this.bold else regular
            text(font, box.x + 2f, box.y + box.h / 2f - 2.5f, ellipsize(value, font, box.w - 4f))
        }

        fun multiLine(box: PdfBox, value: String) {
            val maxLines = ((box.h - 4f) / LeaveRequestPdfRenderer.FIELD_LEAD).toInt().coerceAtLeast(1)
            val lines = wrap(value, regular, box.w - 4f)
            val shown = if (lines.size <= maxLines) lines else {
                lines.take(maxLines - 1) + ellipsize(lines[maxLines - 1] + " …", regular, box.w - 4f)
            }
            var baseline = box.top - 2f - 6.2f
            shown.forEach { line ->
                text(regular, box.x + 2f, baseline, line)
                baseline -= LeaveRequestPdfRenderer.FIELD_LEAD
            }
        }

        fun mark(box: PdfBox) {
            val w = width(bold, 11f, "X")
            cs.beginText()
            cs.setNonStrokingColor(Color.BLACK)
            cs.setFont(bold, 11f)
            cs.newLineAtOffset(box.x + (box.w - w) / 2f, box.y + box.h / 2f - 3.9f)
            cs.showText("X")
            cs.endText()
        }

        /**
         * Podpis skalowany „contain" w pole z marginesem: rozciągnięty albo przycięty
         * podpis wygląda na przerobiony, a to dokument, który ma budzić zaufanie.
         */
        fun signature(box: PdfBox, png: ByteArray) {
            val image = PDImageXObject.createFromByteArray(document, png, "signature")
            val pad = 3f
            val w = box.w - 2 * pad
            val h = box.h - 2 * pad
            val scale = minOf(w / image.width, h / image.height)
            val drawW = image.width * scale
            val drawH = image.height * scale
            cs.drawImage(image, box.x + pad + (w - drawW) / 2f, box.y + pad + (h - drawH) / 2f, drawW, drawH)
        }

        private fun text(font: PDFont, x: Float, y: Float, value: String) {
            val safe = encodable(font, value.replace('\n', ' ').replace('\t', ' '))
            if (safe.isBlank()) return
            cs.beginText()
            cs.setNonStrokingColor(Color.BLACK)
            cs.setFont(font, LeaveRequestPdfRenderer.FIELD_FONT)
            cs.newLineAtOffset(x, y)
            cs.showText(safe)
            cs.endText()
        }

        private fun width(font: PDFont, size: Float, value: String): Float =
            runCatching { font.getStringWidth(encodable(font, value)) / 1000f * size }.getOrDefault(value.length * size * 0.5f)

        private fun ellipsize(value: String, font: PDFont, maxWidth: Float): String {
            val size = LeaveRequestPdfRenderer.FIELD_FONT
            if (width(font, size, value) <= maxWidth) return value
            var cut = value
            while (cut.isNotEmpty() && width(font, size, "$cut...") > maxWidth) cut = cut.dropLast(1)
            return "$cut..."
        }

        private fun wrap(value: String, font: PDFont, maxWidth: Float): List<String> {
            val size = LeaveRequestPdfRenderer.FIELD_FONT
            val lines = mutableListOf<String>()
            value.trim().split("\n").forEach { paragraph ->
                var current = ""
                paragraph.split(" ").filter { it.isNotEmpty() }.forEach { word ->
                    val candidate = if (current.isEmpty()) word else "$current $word"
                    if (width(font, size, candidate) <= maxWidth || current.isEmpty()) {
                        current = candidate
                    } else {
                        lines += current
                        current = word
                    }
                }
                if (current.isNotEmpty()) lines += current
            }
            return lines
        }

        /** Znak bez glifu w foncie wywala zapis całego PDF — wycinamy go z góry. */
        private fun encodable(font: PDFont, value: String): String {
            if (runCatching { font.getStringWidth(value) }.isSuccess) return value
            return value.filter { ch -> runCatching { font.getStringWidth(ch.toString()) }.isSuccess }
        }
    }
}
