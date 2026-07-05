package pl.detailing.crm.signing.infrastructure

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.signing.domain.SignatureRequest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Generates the "Karta Podpisu / Ścieżka Audytu" — an additional final page merged into
 * every signed protocol BEFORE the qualified seal is applied.
 *
 * The page carries the transaction metadata forming the evidentiary chain:
 * document ID, SHA-256 digest of the source document, exact second-precision timestamps,
 * signer identity, requesting employee, and the IP address / device of the tablet.
 * Because the seal (with its qualified timestamp) covers this page, none of these fields
 * can be altered without breaking the seal.
 */
@Service
class AuditTrailPageGenerator {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private val WARSAW = ZoneId.of("Europe/Warsaw")
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss (zzz)")

        private const val MARGIN = 50f
        private const val TITLE_SIZE = 14f
        private const val SECTION_SIZE = 10f
        private const val LABEL_SIZE = 8f
        private const val VALUE_SIZE = 9f
        private const val LINE_GAP = 4f
    }

    /**
     * Append the audit page to [document] (in memory — nothing touches disk).
     */
    fun appendAuditPage(
        document: PDDocument,
        request: SignatureRequest,
        auditEvents: List<SignatureAuditEventEntity>,
        visitNumber: String,
        sealInfo: String
    ) {
        val pagesBefore = document.numberOfPages
        logger.info(
            "[AuditPage] requestId={} protocolId={} — starting audit page generation, " +
                "document currently has {} page(s), auditEvents={}",
            request.id, request.protocolId, pagesBefore, auditEvents.size
        )

        val regular = loadFont(document, "/fonts/LiberationSans-Regular.ttf")
        val bold = loadFont(document, "/fonts/LiberationSans-Bold.ttf") ?: regular

        if (regular == null) {
            logger.warn(
                "[AuditPage] requestId={} — LiberationSans-Regular.ttf could not be loaded from classpath; " +
                    "audit page will be appended but ALL TEXT will be invisible (font=null guard in writeLine). " +
                    "Check that /fonts/LiberationSans-Regular.ttf exists in resources.",
                request.id
            )
        }
        if (bold == null) {
            logger.warn("[AuditPage] requestId={} — bold font also null, falling back to null", request.id)
        }

        val page = PDPage(PDRectangle.A4)
        document.addPage(page)

        PDPageContentStream(document, page).use { cs ->
            var y = page.mediaBox.height - MARGIN

            y = writeLine(cs, bold, TITLE_SIZE, MARGIN, y, "KARTA PODPISU — ŚCIEŻKA AUDYTU")
            y = writeLine(
                cs, regular, LABEL_SIZE, MARGIN, y - 2,
                "Integralna część dokumentu. Strona objęta kwalifikowaną pieczęcią elektroniczną " +
                    "wraz ze znacznikiem czasu."
            )
            y -= 10f

            y = section(cs, bold, y, "IDENTYFIKACJA DOKUMENTU")
            y = field(cs, regular, bold, y, "Nazwa dokumentu", request.documentName)
            y = field(cs, regular, bold, y, "Identyfikator dokumentu (protokołu)", request.protocolId.toString())
            y = field(cs, regular, bold, y, "Identyfikator sesji podpisu", request.id.toString())
            y = field(cs, regular, bold, y, "Numer wizyty", visitNumber)
            y = field(
                cs, regular, bold, y,
                "Skrót SHA-256 dokumentu źródłowego (WYSIWYS)", request.documentSha256
            )
            y -= 6f

            y = section(cs, bold, y, "OSOBY UCZESTNICZĄCE")
            y = field(cs, regular, bold, y, "Osoba podpisująca", request.signerName)
            y = field(
                cs, regular, bold, y,
                "Pracownik żądający podpisu (login CRM)",
                "${request.requestedByName} [${request.requestedBy.value}]"
            )
            y -= 6f

            y = section(cs, bold, y, "URZĄDZENIE I SIEĆ")
            y = field(cs, regular, bold, y, "Adres IP urządzenia podpisującego", request.signerIpAddress ?: "—")
            y = field(cs, regular, bold, y, "Urządzenie (tablet)", request.signerDevice ?: "—")
            request.tabletId?.let { y = field(cs, regular, bold, y, "Identyfikator tabletu", it) }
            y -= 6f

            y = section(cs, bold, y, "OŚWIADCZENIE ZAAKCEPTOWANE PRZED PODPISEM")
            y = paragraph(cs, regular, VALUE_SIZE, y, "„${request.declarationText}”", page)
            y -= 6f

            y = section(cs, bold, y, "PRZEBIEG ZDARZEŃ (z dokładnością do sekundy, czas lokalny Europe/Warsaw)")
            for (event in auditEvents) {
                val line = "${formatInstant(event.occurredAt)}  ·  ${describeEvent(event)}"
                y = paragraph(cs, regular, VALUE_SIZE, y, line, page)
                if (y < MARGIN + 80f) break // keep the seal note on the page
            }
            y -= 6f

            y = section(cs, bold, y, "ZABEZPIECZENIE KRYPTOGRAFICZNE")
            y = paragraph(cs, regular, VALUE_SIZE, y, sealInfo, page)
            y -= 4f
            paragraph(
                cs, regular, LABEL_SIZE, y,
                "Dokument został zabezpieczony zgodnie z zasadą WYSIWYS (What You See Is What You Sign). " +
                    "Podpis odręczny został powiązany kryptograficznie ze skrótem SHA-256 dokumentu " +
                    "wyświetlonego osobie podpisującej. Obraz podpisu został przetworzony wyłącznie " +
                    "w pamięci operacyjnej serwera i trwale usunięty po scaleniu z dokumentem — system " +
                    "nie przechowuje odrębnych plików graficznych podpisów. Integralność i autentyczność " +
                    "dokumentu opieczętowanego kwalifikowaną pieczęcią elektroniczną korzysta z domniemania " +
                    "z art. 35 ust. 2 rozporządzenia eIDAS (UE) nr 910/2014.",
                page
            )
        }

        val pagesAfter = document.numberOfPages
        if (pagesAfter > pagesBefore) {
            logger.info(
                "[AuditPage] requestId={} — audit page appended successfully, " +
                    "document now has {} page(s) (was {}), fontLoaded={}",
                request.id, pagesAfter, pagesBefore, regular != null
            )
        } else {
            logger.error(
                "[AuditPage] requestId={} — page count unchanged after appendAuditPage! " +
                    "before={}, after={} — audit page was NOT added to the document",
                request.id, pagesBefore, pagesAfter
            )
        }
    }

    private fun describeEvent(event: SignatureAuditEventEntity): String {
        val base = when (event.eventType) {
            pl.detailing.crm.signing.domain.SignatureAuditEventType.REQUEST_CREATED ->
                "Utworzono żądanie podpisu i obliczono skrót dokumentu"
            pl.detailing.crm.signing.domain.SignatureAuditEventType.DOCUMENT_DELIVERED ->
                "Dokument dostarczony i wyświetlony na tablecie (skrót zweryfikowany)"
            pl.detailing.crm.signing.domain.SignatureAuditEventType.DECLARATION_ACCEPTED ->
                "Osoba podpisująca zaakceptowała oświadczenie o zapoznaniu się z treścią"
            pl.detailing.crm.signing.domain.SignatureAuditEventType.SIGNATURE_SUBMITTED ->
                "Przesłano podpis z tabletu"
            pl.detailing.crm.signing.domain.SignatureAuditEventType.HASH_VERIFIED ->
                "Serwer potwierdził zgodność skrótu dokumentu z tabletu ze skrótem oczekiwanym"
            pl.detailing.crm.signing.domain.SignatureAuditEventType.DOCUMENT_SEALED ->
                "Nałożono pieczęć elektroniczną ze znacznikiem czasu"
            pl.detailing.crm.signing.domain.SignatureAuditEventType.REQUEST_COMPLETED ->
                "Zakończono proces podpisu — dokument utrwalony"
            pl.detailing.crm.signing.domain.SignatureAuditEventType.REQUEST_CANCELLED ->
                "Żądanie anulowane przez pracownika"
            pl.detailing.crm.signing.domain.SignatureAuditEventType.REQUEST_DECLINED ->
                "Osoba podpisująca odmówiła podpisu"
            pl.detailing.crm.signing.domain.SignatureAuditEventType.REQUEST_FAILED ->
                "Proces podpisu zakończony niepowodzeniem"
        }
        val actor = "wykonawca: ${event.actor}"
        val ip = event.ipAddress?.let { ", IP: $it" } ?: ""
        return "$base ($actor$ip)"
    }

    private fun formatInstant(instant: Instant): String =
        TIMESTAMP_FORMAT.format(instant.atZone(WARSAW))

    private fun section(cs: PDPageContentStream, bold: PDFont?, y: Float, title: String): Float {
        return writeLine(cs, bold, SECTION_SIZE, MARGIN, y, title) - 2f
    }

    private fun field(
        cs: PDPageContentStream,
        regular: PDFont?,
        bold: PDFont?,
        y: Float,
        label: String,
        value: String
    ): Float {
        var newY = writeLine(cs, regular, LABEL_SIZE, MARGIN, y, "$label:")
        newY = writeLine(cs, bold, VALUE_SIZE, MARGIN + 10f, newY, value)
        return newY - LINE_GAP
    }

    /** Write a wrapped paragraph; returns the new Y position. */
    private fun paragraph(
        cs: PDPageContentStream,
        font: PDFont?,
        size: Float,
        startY: Float,
        text: String,
        page: PDPage
    ): Float {
        val maxWidth = page.mediaBox.width - 2 * MARGIN
        var y = startY
        for (line in wrapText(text, font, size, maxWidth)) {
            y = writeLine(cs, font, size, MARGIN, y, line)
        }
        return y
    }

    private fun wrapText(text: String, font: PDFont?, size: Float, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (textWidth(candidate, font, size) > maxWidth && current.isNotEmpty()) {
                lines.add(current.toString())
                current = StringBuilder(word)
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    private fun textWidth(text: String, font: PDFont?, size: Float): Float =
        try {
            (font?.getStringWidth(text) ?: (text.length * 500f)) / 1000f * size
        } catch (e: Exception) {
            text.length * size * 0.55f
        }

    private fun writeLine(
        cs: PDPageContentStream,
        font: PDFont?,
        size: Float,
        x: Float,
        y: Float,
        text: String
    ): Float {
        if (font != null) {
            cs.beginText()
            cs.setFont(font, size)
            cs.newLineAtOffset(x, y)
            cs.showText(sanitizeForFont(text, font))
            cs.endText()
        }
        return y - size - LINE_GAP
    }

    /** Drop glyphs the font cannot encode instead of failing the whole page. */
    private fun sanitizeForFont(text: String, font: PDFont): String =
        text.filter { ch ->
            try {
                font.encode(ch.toString()); true
            } catch (e: Exception) {
                false
            }
        }

    private fun loadFont(document: PDDocument, classpath: String): PDType0Font? =
        javaClass.getResourceAsStream(classpath)?.use { stream ->
            try {
                PDType0Font.load(document, stream, true)
            } catch (e: Exception) {
                null
            }
        }
}
