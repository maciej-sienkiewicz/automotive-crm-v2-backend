package pl.detailing.crm.employee.leaverequest.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.springframework.stereotype.Service
import pl.detailing.crm.employee.leave.domain.LeaveType
import pl.detailing.crm.shared.pdf.DocumentSheet
import pl.detailing.crm.shared.pdf.DocumentStyle
import pl.detailing.crm.shared.pdf.LogoTrim
import java.awt.Color
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Prostokąt na stronie w układzie PDF: [y] to DOLNA krawędź, jednostki w pt. */
data class PdfBox(val x: Float, val y: Float, val w: Float, val h: Float) {
    val top: Float get() = y + h
}

/**
 * Położenie pól, które wypełnia się PO wygenerowaniu dokumentu: przy podpisie pracownika
 * (H1 → H2) i przy decyzji (H2 → H3). Stempel pisze dokładnie tam, gdzie renderer
 * narysował puste szare pola — dlatego oba biorą położenia z jednego miejsca.
 */
data class LeaveRequestPdfLayout(
    val submittedAt: PdfBox,
    val employeeSignature: PdfBox,
    val employeeSignedAt: PdfBox,
    val decisionApproved: PdfBox,
    val decisionRejected: PdfBox,
    val decidedBy: PdfBox,
    val decisionBasis: PdfBox,
    val decisionNote: PdfBox,
    val approverSignature: PdfBox,
    val decidedAt: PdfBox,
    /** Dolna krawędź ostatniego pola treści — musi zostać nad przypisem. */
    val contentBottom: Float,
    /** Górna krawędź przypisu (kreska nad nim). */
    val footnoteTop: Float
)

/** Treść wniosku drukowana przy utworzeniu szkicu. */
data class LeaveRequestPdfData(
    val number: String,
    val submissionMode: String,
    val employerName: String,
    val employerAddress: String?,
    val employerTaxId: String?,
    val logoPng: ByteArray?,
    val employeeName: String,
    val employeeEmail: String?,
    val employeePhone: String?,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val workingDays: Int,
    val leaveType: LeaveType,
    val onDemand: Boolean,
    val reason: String?,
    val substituteName: String?
)

/**
 * Wniosek urlopowy jako PDF, rysowany w kodzie.
 *
 * Backend nie ma renderera HTML → PDF, więc `templates/wniosek_urlopowy.html` jest ŹRÓDŁEM
 * PROJEKTU (siatka, etykiety, kolejność sekcji), a nie plikiem, który się wypełnia — tak
 * samo jak przy raporcie właściciela i wizualizacji faktury. Wymiary poniżej są przepisane
 * z tamtego pliku co do punktu, a prymitywy (belka, szare pole, tytuł) biorą się
 * z [DocumentSheet], żeby wniosek wyglądał jak reszta dokumentów studia.
 *
 * Układ jest NIEZALEŻNY OD TREŚCI: każde pole ma stałą wysokość, a tekst, który się nie
 * mieści, jest skracany. To warunek stempli — podpis pracownika i decyzja trafiają na
 * dokument później, w innym żądaniu, i muszą trafić w te same pola ([layout]). Dlatego
 * długość uzasadnienia i notatki decyzji ogranicza walidacja ([MAX_REASON_LENGTH]).
 */
@Service
class LeaveRequestPdfRenderer {

    companion object {
        /** Tyle znaków uzasadnienia mieści się w polu (4 wiersze) — pilnuje tego walidacja. */
        const val MAX_REASON_LENGTH = 250

        /** Tyle znaków uzasadnienia decyzji mieści się w polu (2 wiersze na całą szerokość). */
        const val MAX_DECISION_NOTE_LENGTH = 250

        const val DATE_PATTERN = "dd.MM.yyyy"
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern(DATE_PATTERN)

        internal const val FIELD_FONT = 7f
        internal const val FIELD_LEAD = 8.98f
        private const val LABEL_FONT = 8f
        private const val BOX_H = 18.42f
        private const val CHECK = 18.42f
        private const val SIG_H = 39.25f
        private const val NOTES_H = 4 * FIELD_LEAD + 4f
        private const val DECISION_NOTE_H = 2 * FIELD_LEAD + 4f

        val STATEMENTS = listOf(
            "Wnoszę o udzielenie urlopu wskazanego rodzaju w terminie określonym powyżej.",
            "Przyjmuję do wiadomości, że złożenie wniosku nie uprawnia do nieobecności w pracy — prawo do " +
                "urlopu powstaje dopiero z chwilą zgody pracodawcy wyrażonej w części „Decyzja pracodawcy”, " +
                "z wyjątkiem urlopu na żądanie, którego pracodawca udziela w terminie wskazanym przez pracownika.",
            "Oświadczam, że dane podane we wniosku są zgodne z prawdą.",
            "Podpisanie wniosku na ekranie urządzenia stanowi oświadczenie woli złożone w formie dokumentowej. " +
                "Dokument jest powiązany z podpisem sumą kontrolną SHA-256, a przebieg podpisywania utrwala " +
                "karta podpisów dołączona do dokumentu."
        )

        const val FOOTNOTE = "Wniosek jest ważny wyłącznie z oboma podpisami. Osoba rozpatrująca działa w imieniu " +
            "pracodawcy jako właściciel studia albo na podstawie uprawnienia „Akceptacja wniosków urlopowych” " +
            "nadanego w systemie; podstawa obowiązująca w chwili decyzji jest wpisana powyżej. Do dokumentu " +
            "dołączona jest karta podpisów z przebiegiem obu sesji podpisywania."
    }

    /**
     * Położenia pól do stempli. Liczone tym samym przebiegiem co [render] (na pustych
     * danych), więc stempel i renderer nie mogą się rozjechać.
     */
    val layout: LeaveRequestPdfLayout by lazy { draw(sample()).second }

    /** Szkic wniosku bez podpisów (H1) — pola decyzji i podpisów zostają puste. */
    fun render(data: LeaveRequestPdfData): ByteArray = draw(data).first

    private fun draw(data: LeaveRequestPdfData): Pair<ByteArray, LeaveRequestPdfLayout> = PDDocument().use { doc ->
        val s = DocumentSheet(doc)
        header(s, data)
        s.title("WNIOSEK URLOPOWY")

        // ── Nr wniosku / data złożenia / sposób złożenia ───────────────────────
        s.y -= 14f
        val metaTop = s.y
        val metaWidths = listOf(128.16f, 183.12f, 189.60f)
        val metaGap = (DocumentStyle.CONTENT_W - metaWidths.sum()) / 2f
        var mx = DocumentStyle.LEFT
        val numberBox = labeledBox(s, mx, metaTop, metaWidths[0], "NR WNIOSKU", BOX_H, 2.55f)
        mx += metaWidths[0] + metaGap
        val submittedAtBox = labeledBox(s, mx, metaTop, metaWidths[1], "DATA I GODZINA ZŁOŻENIA", BOX_H, 2.55f)
        mx += metaWidths[1] + metaGap
        val modeBox = labeledBox(s, mx, metaTop, metaWidths[2], "SPOSÓB ZŁOŻENIA", BOX_H, 2.55f)
        singleLine(s, numberBox, data.number, bold = true)
        singleLine(s, modeBox, data.submissionMode)
        s.y = numberBox.y

        // ── Pracownik / termin ──────────────────────────────────────────────────
        s.y -= 10.22f
        val entityTop = s.y
        val entityW = 261.40f
        val leftX = DocumentStyle.LEFT
        val rightX = DocumentStyle.LEFT + DocumentStyle.CONTENT_W - entityW
        s.tab(leftX, entityTop, "PRACOWNIK", 88f)
        s.tab(rightX, entityTop, "TERMIN URLOPU", 88f)
        var rowTop = entityTop - DocumentStyle.TAB_H
        val employeeRows = listOf(
            "Imię i nazwisko" to data.employeeName,
            "E-mail" to (data.employeeEmail ?: ""),
            "Nr tel." to (data.employeePhone ?: "")
        )
        val termRows = listOf(
            "Od dnia" to data.startDate.format(DATE),
            "Do dnia" to data.endDate.format(DATE),
            "Dni robocze" to data.workingDays.toString()
        )
        employeeRows.zip(termRows).forEach { (employee, term) ->
            rowTop -= 4.71f
            singleLine(s, fieldRow(s, leftX, rowTop, entityW, 70f, employee.first), employee.second)
            singleLine(s, fieldRow(s, rightX, rowTop, entityW, 70f, term.first), term.second, bold = term.first == "Dni robocze")
            rowTop -= BOX_H
        }
        s.y = rowTop

        // ── Rodzaj urlopu ───────────────────────────────────────────────────────
        s.y -= 11f
        s.tab(DocumentStyle.LEFT, s.y, "RODZAJ URLOPU", 104f)
        s.y -= DocumentStyle.TAB_H + 6f
        val options = listOf(
            "Wypoczynkowy" to (data.leaveType == LeaveType.ANNUAL && !data.onDemand),
            "Na żądanie (art. 167² KP)" to (data.leaveType == LeaveType.ANNUAL && data.onDemand),
            "Bezpłatny" to (data.leaveType == LeaveType.UNPAID),
            "Okolicznościowy" to (data.leaveType == LeaveType.SPECIAL),
            "Opieka nad dzieckiem (art. 188 KP)" to (data.leaveType == LeaveType.CARE),
            "Rodzicielski / wychowawczy" to (data.leaveType == LeaveType.PARENTAL)
        )
        val colGap = 13.33f
        val colW = (DocumentStyle.CONTENT_W - 2 * colGap) / 3f
        options.chunked(3).forEachIndexed { row, chunk ->
            val top = s.y - row * (CHECK + 4.73f)
            chunk.forEachIndexed { col, (label, checked) ->
                val box = option(s, DocumentStyle.LEFT + col * (colW + colGap), top, label)
                if (checked) mark(s, box)
            }
        }
        s.y -= 2 * CHECK + 4.73f

        // ── Uzasadnienie / zastępstwo ───────────────────────────────────────────
        s.y -= 13.06f
        val notesTop = s.y
        val notesW = 261.94f
        val reasonBox = labeledBox(s, leftX, notesTop, notesW, "UZASADNIENIE WNIOSKU", NOTES_H, 2.41f, tabW = 132f)
        val substituteBox = labeledBox(
            s, DocumentStyle.LEFT + DocumentStyle.CONTENT_W - notesW, notesTop, notesW, "OSOBA ZASTĘPUJĄCA", NOTES_H, 2.41f,
            tabW = 112f
        )
        multiLine(s, reasonBox, data.reason ?: "")
        multiLine(s, substituteBox, data.substituteName ?: "")
        s.y = reasonBox.y

        // ── Oświadczenia pracownika ─────────────────────────────────────────────
        s.y -= 13.06f
        s.tab(DocumentStyle.LEFT, s.y, "OŚWIADCZENIA PRACOWNIKA", 164f)
        s.y -= DocumentStyle.TAB_H + 8f
        val termsLead = 9.68f
        STATEMENTS.forEachIndexed { index, statement ->
            if (index > 0) s.y -= 3f
            val lines = s.wrap(statement, s.regular, LABEL_FONT, DocumentStyle.CONTENT_W - 18.24f)
            s.text(s.regular, LABEL_FONT, DocumentStyle.LEFT + 0.24f, s.y - 7f, "${index + 1}.", DocumentStyle.INK)
            lines.forEach { line ->
                s.text(s.regular, LABEL_FONT, DocumentStyle.LEFT + 18.24f, s.y - 7f, line, DocumentStyle.INK)
                s.y -= termsLead
            }
        }

        // ── Podpis pracownika ───────────────────────────────────────────────────
        s.y -= 9f
        val (employeeSignature, employeeSignedAt) = signRow(s, "PODPIS PRACOWNIKA", 123.84f, "DATA I GODZINA PODPISU")

        // ── Decyzja pracodawcy ──────────────────────────────────────────────────
        // Część wypełniana przy decyzji odcina się kreską na całą szerokość: pracownik
        // podpisuje dokument z pustą decyzją i widzi, że ta część nie należy do niego.
        s.y -= 10f
        s.cs.setStrokingColor(DocumentStyle.NAVY)
        s.cs.setLineWidth(0.75f)
        s.cs.moveTo(DocumentStyle.LEFT, s.y)
        s.cs.lineTo(DocumentStyle.LEFT + DocumentStyle.CONTENT_W, s.y)
        s.cs.stroke()
        s.y -= 10f
        s.tab(DocumentStyle.LEFT, s.y, "DECYZJA PRACODAWCY", 144f)
        s.y -= DocumentStyle.TAB_H + 6f
        val approveLabel = "Wyrażam zgodę na urlop we wnioskowanym terminie"
        val approvedBox = option(s, DocumentStyle.LEFT, s.y, approveLabel)
        val rejectedX = DocumentStyle.LEFT + CHECK + 6f + s.widthOf(s.regular, LABEL_FONT, approveLabel) + 26f
        val rejectedBox = option(s, rejectedX, s.y, "Nie wyrażam zgody")
        s.y -= CHECK
        s.y -= 4.71f
        val decidedByBox = fieldRow(s, DocumentStyle.LEFT, s.y, DocumentStyle.CONTENT_W, 104f, "Osoba rozpatrująca")
        s.y -= BOX_H + 4.71f
        val basisBox = fieldRow(s, DocumentStyle.LEFT, s.y, DocumentStyle.CONTENT_W, 104f, "Podstawa uprawnienia")
        s.y -= BOX_H + 8f
        val decisionNoteBox = labeledBox(
            s, DocumentStyle.LEFT, s.y, DocumentStyle.CONTENT_W, "UZASADNIENIE DECYZJI", DECISION_NOTE_H, 2.41f, tabW = 132f
        )
        s.y = decisionNoteBox.y

        // ── Podpis osoby rozpatrującej ──────────────────────────────────────────
        s.y -= 9f
        val (approverSignature, decidedAt) = signRow(s, "PODPIS OSOBY ROZPATRUJĄCEJ", 172f, "DATA I GODZINA DECYZJI")
        val contentBottom = s.y

        val footnoteTop = footnote(s)

        val layout = LeaveRequestPdfLayout(
            submittedAt = submittedAtBox,
            employeeSignature = employeeSignature,
            employeeSignedAt = employeeSignedAt,
            decisionApproved = approvedBox,
            decisionRejected = rejectedBox,
            decidedBy = decidedByBox,
            decisionBasis = basisBox,
            decisionNote = decisionNoteBox,
            approverSignature = approverSignature,
            decidedAt = decidedAt,
            contentBottom = contentBottom,
            footnoteTop = footnoteTop
        )
        s.finish() to layout
    }

    // ── Bloki ──────────────────────────────────────────────────────────────────

    /**
     * Logo po lewej i pole „PRACODAWCA" po prawej — zawsze, także bez logo. Wniosek jest
     * oświadczeniem wobec pracodawcy, więc jego dane nie mogą zależeć od tego, czy studio
     * wgrało znak firmowy (nagłówek [DocumentSheet.header] chowa pole bez logo).
     */
    private fun header(s: DocumentSheet, data: LeaveRequestPdfData) {
        val pageTop = DocumentStyle.PAGE_H
        val logoTop = pageTop - 14.42f
        val drewLogo = data.logoPng?.let { png ->
            s.imageFitted(
                LogoTrim.trim(png), "studio-logo",
                DocumentStyle.LEFT - 0.94f, logoTop - DocumentStyle.LOGO_H,
                DocumentStyle.LOGO_W, DocumentStyle.LOGO_H, alignLeft = true
            )
        } ?: false

        val boxW = 180f
        val boxX = DocumentStyle.PAGE_W - DocumentStyle.RIGHT_MARGIN - boxW
        if (!drewLogo) {
            // Nazwa pismem w miejscu logo, jak w pozostałych dokumentach studia.
            s.wrap(data.employerName, s.bold, 13f, boxX - DocumentStyle.LEFT - 12f).take(2).forEachIndexed { i, line ->
                s.text(s.bold, 13f, DocumentStyle.LEFT, logoTop - 22f - i * 16f, line, DocumentStyle.NAVY)
            }
        }
        val tabTop = pageTop - 22.32f
        s.tab(boxX, tabTop, "PRACODAWCA", boxW)
        val boxTop = tabTop - DocumentStyle.TAB_H - 2.28f
        val boxH = 34f
        s.rect(boxX, boxTop - boxH, boxW, boxH, DocumentStyle.GRAY)
        var ty = boxTop - 8.5f
        s.text(s.bold, FIELD_FONT, boxX + 2f, ty, s.ellipsize(data.employerName, s.bold, FIELD_FONT, boxW - 4f), Color.BLACK)
        ty -= 8.5f
        data.employerAddress?.let { address ->
            s.text(s.regular, FIELD_FONT, boxX + 2f, ty, s.ellipsize(address, s.regular, FIELD_FONT, boxW - 4f), Color.BLACK)
            ty -= 8.5f
        }
        data.employerTaxId?.let { nip ->
            s.text(s.regular, FIELD_FONT, boxX + 2f, ty, "NIP $nip", Color.BLACK)
        }
        s.y = boxTop - boxH
    }

    /** Belka z podpisem i szare pole pod nią. */
    private fun labeledBox(
        s: DocumentSheet, x: Float, top: Float, w: Float, label: String, boxH: Float, gap: Float, tabW: Float = w
    ): PdfBox {
        s.tab(x, top, label, tabW)
        val boxTop = top - DocumentStyle.TAB_H - gap
        s.rect(x, boxTop - boxH, w, boxH, DocumentStyle.GRAY)
        return PdfBox(x, boxTop - boxH, w, boxH)
    }

    /** Wiersz „etykieta: [pole]" z etykietą wyrównaną do prawej, jak `.field-row` w szablonie. */
    private fun fieldRow(s: DocumentSheet, x: Float, top: Float, w: Float, labelW: Float, label: String): PdfBox {
        val labelRight = x + labelW
        s.textRight(s.regular, LABEL_FONT, labelRight, top - BOX_H / 2f - 2.8f, label, DocumentStyle.INK)
        val boxX = labelRight + 6.27f
        val box = PdfBox(boxX, top - BOX_H, x + w - boxX, BOX_H)
        s.rect(box.x, box.y, box.w, box.h, DocumentStyle.GRAY)
        return box
    }

    /** Kwadrat do zaznaczenia i etykieta obok. */
    private fun option(s: DocumentSheet, x: Float, top: Float, label: String): PdfBox {
        val box = PdfBox(x, top - CHECK, CHECK, CHECK)
        s.rect(box.x, box.y, box.w, box.h, DocumentStyle.GRAY)
        s.text(s.regular, LABEL_FONT, x + CHECK + 6f, top - CHECK / 2f - 2.8f, label, DocumentStyle.INK)
        return box
    }

    private fun mark(s: DocumentSheet, box: PdfBox) {
        val w = s.widthOf(s.bold, 11f, "X")
        s.text(s.bold, 11f, box.x + (box.w - w) / 2f, box.y + box.h / 2f - 3.9f, "X", Color.BLACK)
    }

    /**
     * Pole podpisu po lewej i pole „data i godzina" po prawej, wyrównane do dołu
     * (`align-items: flex-end` w szablonie).
     */
    private fun signRow(s: DocumentSheet, signLabel: String, signTabW: Float, whenLabel: String): Pair<PdfBox, PdfBox> {
        val colW = 261.94f
        val top = s.y
        val signature = labeledBox(s, DocumentStyle.LEFT, top, colW, signLabel, SIG_H, 2.30f, tabW = signTabW)
        val bottom = signature.y
        val whenX = DocumentStyle.LEFT + DocumentStyle.CONTENT_W - colW
        val whenTabTop = bottom + BOX_H + 2.55f + DocumentStyle.TAB_H
        val whenBox = labeledBox(s, whenX, whenTabTop, colW, whenLabel, BOX_H, 2.55f, tabW = 150f)
        s.y = bottom
        return signature to whenBox
    }

    /** Przypis przy dolnej krawędzi; zwraca położenie kreski nad nim. */
    private fun footnote(s: DocumentSheet): Float {
        val lines = s.wrap(FOOTNOTE, s.regular, FIELD_FONT, DocumentStyle.CONTENT_W)
        val lead = 9f
        val top = 24f + lines.size * lead + 5f
        s.cs.setStrokingColor(DocumentStyle.NAVY)
        s.cs.setLineWidth(0.75f)
        s.cs.moveTo(DocumentStyle.LEFT, top)
        s.cs.lineTo(DocumentStyle.LEFT + DocumentStyle.CONTENT_W, top)
        s.cs.stroke()
        var baseline = top - 5f - 6.5f
        lines.forEach { line ->
            s.text(s.regular, FIELD_FONT, DocumentStyle.LEFT, baseline, line, DocumentStyle.INK)
            baseline -= lead
        }
        return top
    }

    private fun singleLine(s: DocumentSheet, box: PdfBox, value: String, bold: Boolean = false) {
        if (value.isBlank()) return
        val font = if (bold) s.bold else s.regular
        s.text(font, FIELD_FONT, box.x + 2f, box.y + box.h / 2f - 2.5f, s.ellipsize(value, font, FIELD_FONT, box.w - 4f), Color.BLACK)
    }

    private fun multiLine(s: DocumentSheet, box: PdfBox, value: String) {
        val maxLines = ((box.h - 4f) / FIELD_LEAD).toInt().coerceAtLeast(1)
        val lines = s.wrap(value, s.regular, FIELD_FONT, box.w - 4f)
        val shown = if (lines.size <= maxLines) lines else {
            lines.take(maxLines - 1) + s.ellipsize(lines[maxLines - 1] + " …", s.regular, FIELD_FONT, box.w - 4f)
        }
        var baseline = box.top - 2f - 6.2f
        shown.forEach { line ->
            s.text(s.regular, FIELD_FONT, box.x + 2f, baseline, line, Color.BLACK)
            baseline -= FIELD_LEAD
        }
    }

    private fun sample() = LeaveRequestPdfData(
        number = "WU/0000/0000",
        submissionMode = "",
        employerName = "",
        employerAddress = null,
        employerTaxId = null,
        logoPng = null,
        employeeName = "",
        employeeEmail = null,
        employeePhone = null,
        startDate = LocalDate.of(2026, 1, 1),
        endDate = LocalDate.of(2026, 1, 1),
        workingDays = 0,
        leaveType = LeaveType.ANNUAL,
        onDemand = false,
        reason = null,
        substituteName = null
    )
}
