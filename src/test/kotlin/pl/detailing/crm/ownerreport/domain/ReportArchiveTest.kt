package pl.detailing.crm.ownerreport.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Tabela raportów: wszystkie okresy od założenia konta, trwający na górze, i porównanie
 * liczone dokładnie tak, jak liczy je PDF za ten sam okres.
 */
class ReportArchiveTest {

    /** Poniedziałek, 28.09.2026 — trwa tydzień 28.09–04.10. */
    private val today = LocalDate.of(2026, 9, 28)

    private fun sale(day: LocalDate, gross: Long, hour: Int = 12, minute: Int = 0) =
        ClosedSale(LocalDateTime.of(day, LocalTime.of(hour, minute)).atZone(ReportPeriod.ZONE).toInstant(), gross)

    @Test
    fun `trwajacy okres na gorze, pod nim pelne tygodnie od najnowszego do tygodnia zalozenia konta`() {
        val since = LocalDate.of(2026, 9, 3) // czwartek

        val rows = ReportArchive.build(ReportLength.WEEK, ReportComparison.PREVIOUS, since, today, emptyList())

        assertEquals(
            listOf("2026-09-28", "2026-09-21", "2026-09-14", "2026-09-07", "2026-08-31"),
            rows.map { it.period.from.toString() }
        )
        assertTrue(rows.first().inProgress)
        assertNull(rows.first().baseline, "trwającego tygodnia nie porównujemy z pełnym")
        assertTrue(rows.drop(1).none { it.inProgress })
    }

    @Test
    fun `konto zalozone w tym tygodniu - tylko trwajacy okres`() {
        val rows = ReportArchive.build(ReportLength.WEEK, ReportComparison.PREVIOUS, today, today, emptyList())

        assertEquals(1, rows.size)
        assertTrue(rows.single().inProgress)
    }

    @Test
    fun `data zalozenia z przyszlosci (zegar serwera) nie gubi trwajacego okresu`() {
        val rows = ReportArchive.build(ReportLength.MONTH, ReportComparison.PREVIOUS, today.plusDays(3), today, emptyList())

        assertEquals(listOf(LocalDate.of(2026, 9, 1)), rows.map { it.period.from })
    }

    @Test
    fun `poprzedni okres - kwota brutto co do grosza, trwajacy liczy sie do dzis`() {
        val sales = listOf(
            sale(LocalDate.of(2026, 9, 22), 190_000),
            sale(LocalDate.of(2026, 9, 24), 61_500),
            sale(LocalDate.of(2026, 9, 16), 190_000),
            sale(today, 36_900)
        )

        val rows = ReportArchive.build(ReportLength.WEEK, ReportComparison.PREVIOUS, LocalDate.of(2026, 9, 14), today, sales)

        val (current, lastWeek, firstWeek) = rows
        assertEquals(ClosedTotals(1, 36_900), current.closed)
        assertEquals(ClosedTotals(2, 251_500), lastWeek.closed)
        assertEquals(ClosedTotals(1, 190_000), lastWeek.baseline)
        assertEquals(ClosedTotals.ZERO, firstWeek.baseline, "tydzień sprzed założenia konta - zero, jak w PDF-ie")
    }

    @Test
    fun `granica tygodnia o polnocy w Warszawie, nie w UTC`() {
        // 23:30 w niedzielę i 00:30 w poniedziałek w Warszawie to w UTC wciąż niedziela.
        val sundayLate = sale(LocalDate.of(2026, 9, 27), 10_000, hour = 23, minute = 30)
        val mondayEarly = sale(today, 20_000, hour = 0, minute = 30)

        val rows = ReportArchive.build(
            ReportLength.WEEK, ReportComparison.PREVIOUS, LocalDate.of(2026, 9, 1), today, listOf(sundayLate, mondayEarly)
        )

        assertEquals(ClosedTotals(1, 20_000), rows[0].closed)
        assertEquals(ClosedTotals(1, 10_000), rows[1].closed)
    }

    @Test
    fun `mediana z szesciu poprzednich okresow, z zerami sprzed zalozenia konta - jak PDF`() {
        val since = LocalDate.of(2026, 8, 3)
        val weekly = listOf(10_000L, 20_000L, 30_000L, 40_000L) // tygodnie od 03.08
        val sales = weekly.mapIndexed { i, gross -> sale(since.plusWeeks(i.toLong()), gross) }

        val rows = ReportArchive.build(ReportLength.WEEK, ReportComparison.MEDIAN, since, today, sales)
        val week31 = rows.single { it.period.from == LocalDate.of(2026, 8, 31) }

        // Sześć tygodni przed 31.08: dwa sprzed konta (0) i cztery z danymi.
        val expected = MetricsMedian.median(listOf(0L, 0L) + weekly)
        assertEquals(expected, week31.baseline!!.grossCents)
        assertEquals(15_000, week31.baseline!!.grossCents)
        assertEquals(1, week31.baseline!!.count)
    }

    @Test
    fun `dane potrzebne od poczatku okresow porownania - dla mediany szesc wstecz`() {
        val since = LocalDate.of(2026, 9, 3)

        val previous = ReportArchive.dataFrom(ReportLength.WEEK, ReportComparison.PREVIOUS, since, today)
        val median = ReportArchive.dataFrom(ReportLength.WEEK, ReportComparison.MEDIAN, since, today)

        assertEquals(ReportPeriod.containing(ReportLength.WEEK, LocalDate.of(2026, 8, 24)).startInclusive, previous)
        assertEquals(ReportPeriod.containing(ReportLength.WEEK, LocalDate.of(2026, 7, 20)).startInclusive, median)
    }

    @Test
    fun `dwa tygodnie i miesiace wyrownane tak samo jak PDF`() {
        val since = LocalDate.of(2026, 6, 15)

        val months = ReportArchive.build(ReportLength.MONTH, ReportComparison.PREVIOUS, since, today, emptyList())
        val twoWeeks = ReportArchive.build(ReportLength.TWO_WEEKS, ReportComparison.PREVIOUS, since, today, emptyList())

        assertEquals(listOf(9, 8, 7, 6), months.map { it.period.from.monthValue })
        assertTrue(months.all { it.period.from.dayOfMonth == 1 })
        twoWeeks.drop(1).forEach { row ->
            assertEquals(row.period, ReportPeriod.fullStartingOn(ReportLength.TWO_WEEKS, row.period.from, today))
        }
        assertFalse(ReportPeriod.fullStartingOn(ReportLength.TWO_WEEKS, twoWeeks.first().period.from, today) != null)
    }
}
