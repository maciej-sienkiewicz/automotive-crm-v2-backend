package pl.detailing.crm.ownerreport.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Długość okresu raportu. */
enum class ReportLength(val label: String) {
    /** Poniedziałek–niedziela. */
    WEEK("tydzień"),

    /**
     * Dwa pełne tygodnie od poniedziałku, liczone od stałej kotwicy
     * ([ReportPeriod.TWO_WEEK_ANCHOR]) — ten sam podział u wszystkich studiów
     * i w każdym roku, bez zależności od numeracji tygodni ISO (rok z 53 tygodniami
     * rozjechałby „parzyste" i „nieparzyste").
     */
    TWO_WEEKS("2 tygodnie"),

    /** Miesiąc kalendarzowy. */
    MONTH("miesiąc")
}

/** Z czym porównujemy okres raportu. */
enum class ReportComparison {
    /** Okres tej samej długości tuż przed raportowanym. */
    PREVIOUS,

    /**
     * Mediana z [ReportPeriod.MEDIAN_PERIODS] poprzednich okresów. Jeden słaby
     * (urlop) albo mocny (flota) tydzień przed raportowanym nie zmienia wtedy oceny.
     */
    MEDIAN
}

/** Jak często użytkownik chce dostać powiadomienie „Dostępny nowy raport". */
enum class ReportFrequency(val length: ReportLength?) {
    OFF(null),
    WEEKLY(ReportLength.WEEK),
    BIWEEKLY(ReportLength.TWO_WEEKS),
    MONTHLY(ReportLength.MONTH);

    /** Raport jest nowy w dniu, w którym zaczyna się kolejny okres — poprzedni właśnie się domknął. */
    fun isDueOn(today: LocalDate): Boolean = length != null && ReportPeriod.startsOn(length, today)
}

/**
 * Okres raportu: pełne dni kalendarzowe w strefie studia, obie granice WŁĄCZNIE.
 *
 * Raport istnieje wyłącznie za okresy PEŁNE i wyrównane (tydzień od poniedziałku,
 * miesiąc od pierwszego) — porównanie „środa–wtorek" z „poprzednią środą–wtorkiem"
 * nic nie mówi, a raport za trwający tydzień zmienia się z godziny na godzinę.
 *
 * Granice w czasie liczymy o północy w Warszawie, nie w UTC — wizyta wydana
 * w niedzielę o 23:30 należy do tego tygodnia, a nie do następnego.
 */
data class ReportPeriod(val length: ReportLength, val from: LocalDate, val to: LocalDate) {

    init {
        require(!to.isBefore(from)) { "Koniec okresu ($to) jest przed początkiem ($from)" }
    }

    val days: Long get() = ChronoUnit.DAYS.between(from, to) + 1

    val startInclusive: Instant get() = from.atStartOfDay(ZONE).toInstant()

    val endExclusive: Instant get() = to.plusDays(1).atStartOfDay(ZONE).toInstant()

    /** Okres tej samej długości tuż przed tym (dla miesiąca — poprzedni miesiąc kalendarzowy). */
    fun previous(): ReportPeriod = containing(length, from.minusDays(1))

    /** [count] okresów tuż przed tym, od najbliższego. */
    fun precedingPeriods(count: Int): List<ReportPeriod> =
        generateSequence(previous()) { it.previous() }.take(count).toList()

    companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")

        /** Poniedziałek, od którego liczą się okresy dwutygodniowe. */
        val TWO_WEEK_ANCHOR: LocalDate = LocalDate.of(2024, 1, 1)

        /** Z ilu poprzednich okresów liczymy medianę. */
        const val MEDIAN_PERIODS = 6

        /** Ile ostatnich pełnych okresów podajemy do wyboru. */
        const val SELECTABLE_PERIODS = 12

        fun containing(length: ReportLength, day: LocalDate): ReportPeriod = when (length) {
            ReportLength.WEEK -> {
                val monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                ReportPeriod(length, monday, monday.plusDays(6))
            }
            ReportLength.TWO_WEEKS -> {
                val offset = Math.floorMod(ChronoUnit.DAYS.between(TWO_WEEK_ANCHOR, day), 14L)
                val start = day.minusDays(offset)
                ReportPeriod(length, start, start.plusDays(13))
            }
            ReportLength.MONTH -> ReportPeriod(
                length,
                day.withDayOfMonth(1),
                day.with(TemporalAdjusters.lastDayOfMonth())
            )
        }

        fun startsOn(length: ReportLength, day: LocalDate): Boolean = containing(length, day).from == day

        /** Ostatni okres, który już się skończył — trwający nie jest jeszcze raportem. */
        fun latestFull(length: ReportLength, today: LocalDate): ReportPeriod = containing(length, today).previous()

        /** Pełne okresy do wyboru, od najnowszego. */
        fun recentFull(length: ReportLength, today: LocalDate, count: Int = SELECTABLE_PERIODS): List<ReportPeriod> =
            generateSequence(latestFull(length, today)) { it.previous() }.take(count).toList()

        /**
         * Okres zaczynający się w [from], o ile jest wyrównany i już pełny; inaczej null.
         * Tak adres `?from=` nie przemyci raportu za „środę–wtorek" ani za trwający tydzień.
         */
        fun fullStartingOn(length: ReportLength, from: LocalDate, today: LocalDate): ReportPeriod? {
            val period = containing(length, from)
            return period.takeIf { it.from == from && it.to.isBefore(today) }
        }
    }
}
