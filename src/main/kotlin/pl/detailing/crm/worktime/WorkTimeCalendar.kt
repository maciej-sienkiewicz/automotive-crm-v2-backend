package pl.detailing.crm.worktime

import pl.detailing.crm.employee.leave.domain.LeaveType
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveEntity
import pl.detailing.crm.employee.leaverequest.domain.PolishHolidays
import pl.detailing.crm.worktime.infrastructure.WorkTimeEntryEntity
import java.time.LocalDate
import java.time.YearMonth

/**
 * Kalendarz karty czasu pracy: dni robocze, urlopy, norma i braki — liczone w JEDNYM
 * miejscu dla przeglądu miesiąca, karty menedżera, karty pracownika i „Uzupełnij miesiąc".
 *
 * Dzień roboczy to pon–pt, który nie jest świętem ustawowym — te same [PolishHolidays],
 * którymi liczy się dni wniosku urlopowego. Gdyby karta i wniosek liczyły dni inaczej,
 * pracownik z urlopem na cały tydzień miałby „brak" w Wigilię albo w Boże Ciało.
 *
 * Urlop i L4 zaznaczamy tylko w dni robocze, tak jak lista obecności (PDF): sobota
 * w środku urlopu jest wolna z definicji i napis „URLOP" niczego by tam nie dodał.
 */
object WorkTimeCalendar {

    const val STANDARD_DAY_MINUTES = 480

    /** Dzień karty — wszystko, czego potrzebuje widok jednego wiersza. */
    data class Day(
        val date: LocalDate,
        val entry: WorkTimeEntryEntity?,
        val isWorkingDay: Boolean,
        val holidayName: String?,
        val leave: LeaveType?,
        val missing: Boolean
    )

    /** Miesiąc jednej osoby: dni i liczby z nich wynikające. */
    data class Month(
        val yearMonth: YearMonth,
        val days: List<Day>,
        val workingDays: Int,
        val leaveWorkingDays: Int,
        val expectedMinutes: Int,
        val missingWorkingDays: Int,
        val totalMinutes: Int,
        val overtimeMinutes: Int
    )

    fun holidaysOf(yearMonth: YearMonth): Map<LocalDate, String> =
        PolishHolidays.forYear(yearMonth.year)
            .filter { YearMonth.from(it.date) == yearMonth }
            .associate { it.date to it.name }

    fun workingDaysOf(yearMonth: YearMonth): Int =
        PolishHolidays.workingDaysBetween(yearMonth.atDay(1), yearMonth.atEndOfMonth())

    /**
     * Dni miesiąca objęte urlopem lub L4 — z modułu urlopów, przycięte do miesiąca.
     * Dwa wpisy na ten sam dzień: wygrywa późniejszy na liście, jak w PDF.
     */
    fun leaveByDay(yearMonth: YearMonth, leaves: List<EmployeeLeaveEntity>): Map<LocalDate, LeaveType> {
        val from = yearMonth.atDay(1)
        val to = yearMonth.atEndOfMonth()
        val result = mutableMapOf<LocalDate, LeaveType>()
        leaves.forEach { leave ->
            var day = maxOf(leave.startDate, from)
            val last = minOf(leave.endDate, to)
            while (!day.isAfter(last)) {
                result[day] = leave.leaveType
                day = day.plusDays(1)
            }
        }
        return result
    }

    /**
     * @param today dzień, do którego (włącznie) brak wpisu jest „brakiem". Miesiąc
     *        przeszły — wszystkie dni robocze; przyszły — żaden.
     */
    fun month(
        yearMonth: YearMonth,
        entries: List<WorkTimeEntryEntity>,
        leaveByDay: Map<LocalDate, LeaveType>,
        today: LocalDate
    ): Month {
        val holidays = holidaysOf(yearMonth)
        val entryByDate = entries.associateBy { it.date }
        val days = (1..yearMonth.lengthOfMonth()).map { dayOfMonth ->
            val date = yearMonth.atDay(dayOfMonth)
            val working = PolishHolidays.isWorkingDay(date)
            val leave = leaveByDay[date]?.takeIf { working }
            val entry = entryByDate[date]
            Day(
                date = date,
                entry = entry,
                isWorkingDay = working,
                holidayName = holidays[date],
                leave = leave,
                missing = working && leave == null && entry == null && !date.isAfter(today)
            )
        }
        val workingDays = days.count { it.isWorkingDay }
        val leaveWorkingDays = days.count { it.leave != null }
        return Month(
            yearMonth = yearMonth,
            days = days,
            workingDays = workingDays,
            leaveWorkingDays = leaveWorkingDays,
            expectedMinutes = (workingDays - leaveWorkingDays) * STANDARD_DAY_MINUTES,
            missingWorkingDays = days.count { it.missing },
            totalMinutes = entries.sumOf { it.minutes },
            overtimeMinutes = entries.sumOf { maxOf(0, it.minutes - STANDARD_DAY_MINUTES) }
        )
    }

    /**
     * Napis dnia wolnego na karcie. Zwolnienie lekarskie nie jest urlopem — tak samo jak
     * na liście obecności, gdzie L4 ma osobny napis.
     */
    fun leaveLabel(type: LeaveType): String = when (type) {
        LeaveType.SICK -> "L4"
        LeaveType.ANNUAL -> "Urlop"
        LeaveType.UNPAID -> "Urlop bezpłatny"
        LeaveType.SPECIAL -> "Urlop okolicznościowy"
        LeaveType.PARENTAL -> "Urlop rodzicielski"
        LeaveType.CARE -> "Opieka nad dzieckiem"
    }
}

/** „Wrzesień 2026" → „wrzesień 2026" — w środku zdania („Karta za wrzesień 2026…"). */
fun YearMonth.toPolishLabelInSentence(): String = toPolishLabel().replaceFirstChar { it.lowercase() }
