package pl.detailing.crm.ownerreport.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/** Jak często studio dostaje raport mailem. */
enum class ReportFrequency(val weeks: Int) {
    OFF(0),
    WEEKLY(1),
    BIWEEKLY(2);

    /**
     * Czy raport ma wyjść w poniedziałek [today].
     *
     * Dwutygodniowy wychodzi w poniedziałki PARZYSTYCH tygodni ISO — ten sam rytm
     * u wszystkich studiów, bez przechowywania „kiedy wysłano ostatnio". Tydzień 53
     * (rok z 53 tygodniami) daje wtedy raz na kilka lat dwa raporty pod rząd po trzy
     * tygodnie przerwy; to lepsze niż stan, który potrafi się rozjechać.
     */
    fun isDueOn(today: LocalDate): Boolean = when (this) {
        OFF -> false
        WEEKLY -> today.dayOfWeek == DayOfWeek.MONDAY
        BIWEEKLY -> today.dayOfWeek == DayOfWeek.MONDAY && today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) % 2 == 0
    }
}

/**
 * Okres raportu: pełne dni kalendarzowe w strefie studia, obie granice WŁĄCZNIE.
 *
 * Granice w czasie liczymy o północy w Warszawie, nie w UTC — wizyta wydana
 * w niedzielę o 23:30 należy do tego tygodnia, a nie do następnego.
 */
data class ReportPeriod(val from: LocalDate, val to: LocalDate) {

    init {
        require(!to.isBefore(from)) { "Koniec okresu ($to) jest przed początkiem ($from)" }
    }

    val days: Long get() = ChronoUnit.DAYS.between(from, to) + 1

    val startInclusive: Instant get() = from.atStartOfDay(ZONE).toInstant()

    val endExclusive: Instant get() = to.plusDays(1).atStartOfDay(ZONE).toInstant()

    /** Okres tej samej długości tuż przed tym — punkt odniesienia dla strzałek w raporcie. */
    fun previous(): ReportPeriod = ReportPeriod(from.minusDays(days), from.minusDays(1))

    companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")

        /** Najdłuższy okres, jaki da się zamówić — raport ma być tygodniowy, nie roczny. */
        const val MAX_DAYS = 93L

        /**
         * Ostatnie [weeks] pełnych tygodni (poniedziałek–niedziela) zakończonych przed [today].
         * W poniedziałek to tydzień, który skończył się wczoraj; w niedzielę — poprzedni,
         * bo bieżący jeszcze trwa.
         */
        fun lastFullWeeks(weeks: Int, today: LocalDate): ReportPeriod {
            require(weeks >= 1) { "Okres musi mieć co najmniej jeden tydzień" }
            val end = today.with(TemporalAdjusters.previous(DayOfWeek.SUNDAY))
            return ReportPeriod(end.minusWeeks(weeks.toLong()).plusDays(1), end)
        }
    }
}
