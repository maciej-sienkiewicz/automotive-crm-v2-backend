package pl.detailing.crm.ownerreport.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.springframework.stereotype.Service
import pl.detailing.crm.ownerreport.domain.OwnerReport
import pl.detailing.crm.ownerreport.domain.PeriodMetrics
import pl.detailing.crm.shared.pdf.DocumentSheet
import pl.detailing.crm.shared.pdf.DocumentStyle
import java.awt.Color
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * PDF raportu właściciela: „co się wydarzyło w firmie i na czym stoję".
 *
 * Układ: kafle z czterema liczbami, które właściciel czyta jako pierwsze, pod nimi
 * cztery moduły tabel (finanse, hala, komunikacja, marketing) z kolumną poprzedniego
 * okresu i zmianą. Każda liczba ma obok punkt odniesienia — sama liczba nie mówi,
 * czy tydzień był dobry.
 *
 * Kolor tylko przy zmianie, i tylko tam, gdzie kierunek jest jednoznaczny: więcej
 * sprzedaży jest dobrze, dłuższe czekanie klienta jest źle. Kosztów nie kolorujemy —
 * wzrost kosztów bywa inwestycją, a czerwień sugerowałaby problem.
 *
 * Bez komentarza AI i bez „utraconych leadów" — decyzja właściciela produktu: statusy
 * leadów bywają ustawiane niestarannie, a raport ma nieść wyłącznie liczby, którym
 * można ufać.
 */
@Service
class OwnerReportPdfRenderer {

    fun render(report: OwnerReport): ByteArray = PDDocument().use { doc ->
        val sheet = DocumentSheet(doc)
        with(sheet) {
            header(report.logoPng, report.studioName, report.studioAddress)
            title(titleFor(report))
            metaRow(
                listOf(
                    "OKRES" to ReportFormat.range(report.period.from, report.period.to),
                    "PORÓWNANIE Z" to report.period.previous().let { ReportFormat.range(it.from, it.to) },
                    "WYGENEROWANO" to GENERATED.format(report.generatedAt.atZone(ZONE))
                )
            )
            kpiTiles(this, report)
            finance(this, report)
            operations(this, report)
            communication(this, report)
            marketing(this, report)
            footnotes(this)
        }
        sheet.finish()
    }

    private fun titleFor(report: OwnerReport): String = when (report.period.days) {
        7L -> "RAPORT TYGODNIOWY"
        14L -> "RAPORT DWUTYGODNIOWY"
        else -> "RAPORT ZA OKRES"
    }

    // ── Kafle ────────────────────────────────────────────────────────────────

    private fun kpiTiles(s: DocumentSheet, r: OwnerReport) {
        val c = r.current
        val p = r.previous
        val tiles = listOf(
            Tile("SPRZEDAŻ BRUTTO", ReportFormat.money(c.closed.grossCents), change(c.closed.grossCents, p.closed.grossCents, Direction.UP)),
            Tile("WIZYTY ROZPOCZĘTE", ReportFormat.count(c.visitsStarted), change(c.visitsStarted, p.visitsStarted, Direction.UP)),
            Tile("NOWE REZERWACJE", ReportFormat.count(c.reservationsCreated), change(c.reservationsCreated, p.reservationsCreated, Direction.UP)),
            Tile("KOSZTY NETTO", ReportFormat.money(c.costsNetCents), change(c.costsNetCents, p.costsNetCents, Direction.NEUTRAL))
        )

        s.y -= 14f
        val top = s.y
        val gap = 8f
        val w = (DocumentStyle.CONTENT_W - gap * (tiles.size - 1)) / tiles.size
        val boxH = 40f
        tiles.forEachIndexed { index, tile ->
            val x = DocumentStyle.LEFT + index * (w + gap)
            s.tab(x, top, tile.label, w)
            val boxTop = top - DocumentStyle.TAB_H - 2.5f
            s.rect(x, boxTop - boxH, w, boxH, DocumentStyle.GRAY)
            val valueSize = fitSize(s, tile.value, w - 8f, 15f)
            s.text(s.bold, valueSize, x + 4f, boxTop - 19f, tile.value, DocumentStyle.NAVY)
            s.text(s.regular, DocumentStyle.NOTE_FONT, x + 4f, boxTop - 33f, "${tile.change.text} vs poprzedni okres", tile.change.color)
        }
        s.y = top - DocumentStyle.TAB_H - 2.5f - boxH
    }

    private fun fitSize(s: DocumentSheet, value: String, maxW: Float, preferred: Float): Float {
        var size = preferred
        while (size > 9f && s.widthOf(s.bold, size, value) > maxW) size -= 0.5f
        return size
    }

    // ── Moduły ───────────────────────────────────────────────────────────────

    private fun finance(s: DocumentSheet, r: OwnerReport) {
        val c = r.current
        val p = r.previous
        val table = Table(s, "FINANSE I SPRZEDAŻ", rows = 10)
        table.row("Wizyty zamknięte (wydane klientom)", c.closed.count, p.closed.count, Direction.UP)
        table.moneyRow("Wartość zamkniętych wizyt brutto", c.closed.grossCents, p.closed.grossCents, Direction.UP)
        table.moneyRow("Wartość zamkniętych wizyt netto", c.closed.netCents, p.closed.netCents, Direction.UP)
        table.row(
            "Średnia wartość wizyty brutto",
            c.closed.averageGrossCents?.let(ReportFormat::money) ?: "—",
            p.closed.averageGrossCents?.let(ReportFormat::money) ?: "—",
            changeOrNull(c.closed.averageGrossCents, p.closed.averageGrossCents, Direction.UP)
        )
        table.moneyRow("Koszty netto (dokumenty z okresu)", c.costsNetCents, p.costsNetCents, Direction.NEUTRAL)
        table.moneyRow(
            "Sprzedaż netto minus koszty netto",
            c.closed.netCents - c.costsNetCents,
            p.closed.netCents - p.costsNetCents,
            Direction.UP
        )
        table.row(
            "Propozycje upsellu (usługi / wizyty)",
            "${ReportFormat.count(c.upsell.suggested)} / ${ReportFormat.count(c.upsell.visitsWithSuggestions)}",
            "${ReportFormat.count(p.upsell.suggested)} / ${ReportFormat.count(p.upsell.visitsWithSuggestions)}",
            change(c.upsell.suggested, p.upsell.suggested, Direction.UP)
        )
        table.moneyRow("Wartość zaproponowanych usług brutto", c.upsell.suggestedGrossCents, p.upsell.suggestedGrossCents, Direction.UP)
        table.row("Upsell potwierdzony przez klientów", c.upsell.accepted, p.upsell.accepted, Direction.UP)
        table.moneyRow("Wartość potwierdzonego upsellu brutto", c.upsell.acceptedGrossCents, p.upsell.acceptedGrossCents, Direction.UP)
    }

    private fun operations(s: DocumentSheet, r: OwnerReport) {
        val c = r.current
        val p = r.previous
        val table = Table(s, "OPERACJE NA HALI", rows = 7)
        table.row("Wizyty rozpoczęte", c.visitsStarted, p.visitsStarted, Direction.UP)
        table.row("Rezerwacje utworzone", c.reservationsCreated, p.reservationsCreated, Direction.UP)
        table.row("Karty wizyt wysłane klientom", c.visitCardsSent, p.visitCardsSent, Direction.UP)
        table.row("Zlecenia zbiorcze: auta obsłużone", c.batch.vehicles, p.batch.vehicles, Direction.UP)
        table.moneyRow("Zlecenia zbiorcze: wartość brutto", c.batch.grossCents, p.batch.grossCents, Direction.UP)
        table.row("Zlecenia zbiorcze: kontrahenci", c.batch.contractors, p.batch.contractors, Direction.NEUTRAL)
        table.row(
            "Do rozliczenia z kontrahentami (stan na dziś)",
            "${ReportFormat.count(r.snapshot.batchUnsettledVehicles)} aut, ${ReportFormat.money(r.snapshot.batchUnsettledGrossCents)}",
            "",
            null
        )
    }

    private fun communication(s: DocumentSheet, r: OwnerReport) {
        val c = r.current.emails
        val p = r.previous.emails
        val table = Table(s, "KOMUNIKACJA Z KLIENTAMI", rows = 6)
        table.row("Maile napisane przez zespół", c.sentByTeam, p.sentByTeam, Direction.NEUTRAL)
        table.row("Maile wysłane automatycznie przez CRM", c.sentAutomated, p.sentAutomated, Direction.NEUTRAL)
        table.row("Zapytania klientów (maile czekające na nas)", c.replies.inquiries, p.replies.inquiries, Direction.NEUTRAL)
        table.row(
            "Mediana czasu odpowiedzi",
            c.replies.medianMinutes?.let(ReportFormat::duration) ?: "—",
            p.replies.medianMinutes?.let(ReportFormat::duration) ?: "—",
            changeOrNull(c.replies.medianMinutes, p.replies.medianMinutes, Direction.DOWN)
        )
        table.row(
            "Odpowiedź w ciągu 1 godziny",
            ReportFormat.percent(c.replies.answeredWithinHour, c.replies.inquiries),
            ReportFormat.percent(p.replies.answeredWithinHour, p.replies.inquiries),
            null
        )
        table.row("Zapytania wciąż bez odpowiedzi", c.replies.unanswered, p.replies.unanswered, Direction.DOWN)
    }

    private fun marketing(s: DocumentSheet, r: OwnerReport) {
        val c = r.current
        val p = r.previous
        val table = Table(s, "MARKETING", rows = 8)
        val ig = c.instagram
        if (ig == null) {
            table.note("Instagram: wskaż własny profil w module Marketing, żeby raport liczył posty.")
        } else {
            val pig = p.instagram
            table.row("Posty na Instagramie", ig.posts, pig?.posts ?: 0, Direction.UP)
            table.row("Polubienia tych postów", ig.likes, pig?.likes ?: 0, Direction.UP)
            table.row("Komentarze pod tymi postami", ig.comments, pig?.comments ?: 0, Direction.UP)
        }

        val competitors = r.snapshot.competitors
        if (competitors == null) {
            table.note("Konkurencja: ustaw swój rejon w monitoringu reklam, żeby raport pokazywał kampanie w okolicy.")
        } else {
            table.row("Konkurenci reklamujący się w Twoim rejonie", ReportFormat.count(competitors.advertisers), "", null)
            table.row("Ich aktywne reklamy (stan na dziś)", ReportFormat.count(competitors.activeAds), "", null)
            table.row("Kampanie konkurencji, które ruszyły w okresie", ReportFormat.count(competitors.campaignsStartedInPeriod), "", null)
            if (competitors.newAdvertisers.isNotEmpty()) {
                table.note("Nowi w rejonie: " + competitors.newAdvertisers.joinToString(", "))
            }
            if (competitors.top.isNotEmpty()) {
                table.note(
                    "Najaktywniejsi: " + competitors.top.joinToString(", ") { (name, ads) -> "$name ($ads)" }
                )
            }
        }
    }

    private fun footnotes(s: DocumentSheet) {
        val lines = FOOTNOTES.flatMap { s.wrap(it, s.regular, 6.5f, DocumentStyle.CONTENT_W) }
        s.ensure(12f + lines.size * 8.5f)
        s.y -= 12f
        lines.forEach { line ->
            s.text(s.regular, 6.5f, DocumentStyle.LEFT, s.y - 6.5f, line, DocumentStyle.MUTED)
            s.y -= 8.5f
        }
    }

    // ── Tabela modułu ────────────────────────────────────────────────────────

    /** Kierunek, w którym zmiana jest dobra — decyduje o kolorze, nigdy o liczbie. */
    private enum class Direction { UP, DOWN, NEUTRAL }

    private data class Change(val text: String, val color: Color)

    private data class Tile(val label: String, val value: String, val change: Change)

    private fun change(current: Long, previous: Long, direction: Direction, threshold: Long = 0): Change {
        val text = ReportFormat.change(current, previous, threshold)
        val color = when {
            current == previous || direction == Direction.NEUTRAL -> DocumentStyle.MUTED
            (current > previous) == (direction == Direction.UP) -> GOOD
            else -> BAD
        }
        return Change(text, color)
    }

    private fun change(current: Int, previous: Int, direction: Direction): Change =
        change(current.toLong(), previous.toLong(), direction, SMALL_COUNT)

    private fun changeOrNull(current: Long?, previous: Long?, direction: Direction): Change? =
        if (current == null || previous == null) null else change(current, previous, direction)

    /**
     * @param rows przewidywana liczba wierszy — moduł, który zmieści się na nowej stronie,
     *   nie jest dzielony: pół tabeli marketingu na dole kartki i pół na następnej
     *   czyta się jak dwa różne moduły.
     */
    private inner class Table(private val s: DocumentSheet, label: String, rows: Int) {
        private var shade = false

        init {
            val whole = SECTION_GAP + DocumentStyle.TAB_H + 3f + ROW_H * rows
            val usable = DocumentStyle.PAGE_H - DocumentStyle.TOP - DocumentStyle.BOTTOM
            s.ensure(minOf(whole, usable))
            s.y -= SECTION_GAP
            val top = s.y
            s.tab(DocumentStyle.LEFT, top, label)
            val baseline = top - DocumentStyle.TAB_H + 4.2f
            s.textRight(s.regular, DocumentStyle.META_FONT, COL_VALUE, baseline, "TEN OKRES", DocumentStyle.MUTED)
            s.textRight(s.regular, DocumentStyle.META_FONT, COL_PREV, baseline, "POPRZEDNI", DocumentStyle.MUTED)
            s.textRight(s.regular, DocumentStyle.META_FONT, COL_CHANGE, baseline, "ZMIANA", DocumentStyle.MUTED)
            s.y = top - DocumentStyle.TAB_H - 3f
        }

        fun row(label: String, current: Int, previous: Int, direction: Direction) =
            row(label, ReportFormat.count(current), ReportFormat.count(previous), change(current, previous, direction))

        fun row(label: String, current: Long, previous: Long, direction: Direction) =
            row(label, ReportFormat.count(current), ReportFormat.count(previous), change(current, previous, direction, SMALL_COUNT))

        fun moneyRow(label: String, current: Long, previous: Long, direction: Direction) =
            row(label, ReportFormat.money(current), ReportFormat.money(previous), change(current, previous, direction))

        fun row(label: String, current: String, previous: String, change: Change?) {
            s.ensure(ROW_H)
            val top = s.y
            if (shade) s.rect(DocumentStyle.LEFT, top - ROW_H, DocumentStyle.CONTENT_W, ROW_H, DocumentStyle.GRAY)
            shade = !shade
            val baseline = top - ROW_H + 4.6f
            s.text(
                s.regular, DocumentStyle.BODY, DocumentStyle.LEFT + 4f, baseline,
                s.ellipsize(label, s.regular, DocumentStyle.BODY, LABEL_W), DocumentStyle.INK
            )
            s.textRight(s.bold, DocumentStyle.BODY, COL_VALUE, baseline, current, DocumentStyle.INK)
            s.textRight(s.regular, DocumentStyle.BODY, COL_PREV, baseline, previous, DocumentStyle.MUTED)
            change?.let { s.textRight(s.bold, DocumentStyle.NOTE_FONT, COL_CHANGE, baseline, it.text, it.color) }
            s.y = top - ROW_H
        }

        /** Zdanie zamiast liczby: brak konfiguracji albo lista nazw. */
        fun note(text: String) {
            val lines = s.wrap(text, s.regular, DocumentStyle.NOTE_FONT, DocumentStyle.CONTENT_W - 8f)
            lines.forEach { line ->
                s.ensure(NOTE_H)
                s.text(s.regular, DocumentStyle.NOTE_FONT, DocumentStyle.LEFT + 4f, s.y - NOTE_H + 3.5f, line, DocumentStyle.MUTED)
                s.y -= NOTE_H
            }
        }
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")
        val GENERATED: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

        val GOOD = Color(0x16, 0x7A, 0x3E)
        val BAD = Color(0xB4, 0x23, 0x18)

        const val ROW_H = 14f
        const val NOTE_H = 11f
        const val SECTION_GAP = 14f

        val RIGHT = DocumentStyle.PAGE_W - DocumentStyle.RIGHT_MARGIN
        val COL_CHANGE = RIGHT - 4f
        val COL_PREV = RIGHT - 70f
        val COL_VALUE = RIGHT - 165f
        val LABEL_W = COL_VALUE - 110f - DocumentStyle.LEFT - 4f

        /** Poniżej tylu sztuk procent nic nie mówi — pokazujemy różnicę w sztukach. */
        const val SMALL_COUNT = 10L

        val FOOTNOTES = listOf(
            "Sprzedaż = wartość wizyt wydanych klientom w okresie, z cen zapisanych na wizytach (brutto co do grosza). " +
                "Koszty = dokumenty kosztowe wystawione w okresie, opłacone i nieopłacone, netto. " +
                "Wizyta rozpoczęta = podpisane przyjęcie auta. Rezerwacja cykliczna liczy się raz.",
            "Czas odpowiedzi liczymy zegarowo (z nocami i weekendami) od pierwszego maila klienta po naszej ostatniej " +
                "odpowiedzi do naszej kolejnej, tylko w rozmowach z klientami — bez newsletterów i spamu. " +
                "Kampanie konkurencji: Biblioteka reklam Meta, reklamy wyświetlane w Twoim rejonie."
        )
    }
}
