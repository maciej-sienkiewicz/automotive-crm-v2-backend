package pl.detailing.crm.ownerreport.domain

import java.time.Instant
import java.time.LocalDate

/** Wizyta wydana klientowi: kiedy i za ile (brutto zapisane na pozycjach, co do grosza). */
data class ClosedSale(val pickupAt: Instant, val grossCents: Long)

/** Wizyty zamknięte w okresie — te same dwie liczby, które PDF pokazuje jako „Sprzedaż brutto" i „Wizyty zamknięte". */
data class ClosedTotals(val count: Int, val grossCents: Long) {
    companion object {
        val ZERO = ClosedTotals(0, 0)
    }
}

/**
 * Wiersz tabeli raportów. [baseline] jest null tylko dla okresu trwającego — trwającego
 * tygodnia nie porównujemy z pełnym, bo w środę każdy tydzień wygląda jak spadek.
 */
data class ReportArchiveRow(
    val period: ReportPeriod,
    val inProgress: Boolean,
    val closed: ClosedTotals,
    val baseline: ClosedTotals?
)

/**
 * Tabela raportów w Statystykach: każdy okres od założenia konta, od najnowszego,
 * z trwającym na górze.
 *
 * Liczby wiersza są dokładnie tymi z PDF-u za ten okres (wizyty wydane w okresie, kwota
 * z [pl.detailing.crm.visit.domain.Visit.settledGross]), a punkt odniesienia liczy się tak
 * samo jak w PDF-ie: poprzedni okres albo mediana [ReportPeriod.MEDIAN_PERIODS] poprzednich,
 * także tych sprzed założenia konta (wtedy zero — tak samo zrobi PDF). Tabela, która
 * mówi „+12%", a PDF z tego wiersza „+9%", podważa oba.
 */
object ReportArchive {

    /** Pierwszy okres tabeli: ten, w którym założono konto. */
    fun firstPeriod(length: ReportLength, since: LocalDate, today: LocalDate): ReportPeriod =
        ReportPeriod.containing(length, minOf(since, today))

    /**
     * Od kiedy potrzeba danych: pierwszy okres tabeli razem z okresami, z którymi się porównuje
     * (dla mediany sześć wstecz). Sprzed założenia konta zwykle nic nie ma, ale historia
     * przeniesiona z innego systemu może mieć — i PDF ją policzy.
     */
    fun dataFrom(length: ReportLength, comparison: ReportComparison, since: LocalDate, today: LocalDate): Instant {
        val first = firstPeriod(length, since, today)
        val back = if (comparison == ReportComparison.MEDIAN) ReportPeriod.MEDIAN_PERIODS else 1
        return first.precedingPeriods(back).last().startInclusive
    }

    fun build(
        length: ReportLength,
        comparison: ReportComparison,
        since: LocalDate,
        today: LocalDate,
        sales: List<ClosedSale>
    ): List<ReportArchiveRow> {
        val byPeriod = sales
            .groupBy { ReportPeriod.containing(length, it.pickupAt.atZone(ReportPeriod.ZONE).toLocalDate()).from }
            .mapValues { (_, inPeriod) -> ClosedTotals(inPeriod.size, inPeriod.sumOf { it.grossCents }) }
        fun totals(period: ReportPeriod) = byPeriod[period.from] ?: ClosedTotals.ZERO

        val current = ReportPeriod.containing(length, today)
        val first = firstPeriod(length, since, today)
        val full = generateSequence(current.previous()) { it.previous() }
            .takeWhile { !it.from.isBefore(first.from) }
            .map { period ->
                val baseline = when (comparison) {
                    ReportComparison.PREVIOUS -> totals(period.previous())
                    ReportComparison.MEDIAN -> period.precedingPeriods(ReportPeriod.MEDIAN_PERIODS).map(::totals).let { periods ->
                        ClosedTotals(
                            count = MetricsMedian.median(periods.map { it.count.toLong() })!!.toInt(),
                            grossCents = MetricsMedian.median(periods.map { it.grossCents })!!
                        )
                    }
                }
                ReportArchiveRow(period, inProgress = false, closed = totals(period), baseline = baseline)
            }
            .toList()

        return listOf(ReportArchiveRow(current, inProgress = true, closed = totals(current), baseline = null)) + full
    }
}
