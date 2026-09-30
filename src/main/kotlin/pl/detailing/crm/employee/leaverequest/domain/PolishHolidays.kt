package pl.detailing.crm.employee.leaverequest.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay

/**
 * Dni ustawowo wolne od pracy w Polsce (ustawa z 18 stycznia 1951 r. o dniach wolnych
 * od pracy) i liczenie dni roboczych urlopu.
 *
 * Urlop udziela się w dniach, które są dla pracownika dniami pracy (art. 154² KP), więc
 * wniosek liczy poniedziałki–piątki bez świąt. Liczba jest drukowana na dokumencie
 * i zamrażana przy utworzeniu wniosku — dlatego liczy ją backend, a nie przeglądarka.
 *
 * Święta ruchome liczymy od Wielkanocy: Poniedziałek Wielkanocny (+1), Zielone Świątki
 * (+49, zawsze niedziela) i Boże Ciało (+60, zawsze czwartek). Wigilia jest dniem wolnym
 * od 2025 r. (ustawa z 6 grudnia 2024 r.), Trzech Króli od 2011 r. — wcześniejszych lat
 * wniosek i tak nie dotyczy, ale reguła ma zgadzać się z prawem dla każdego roku.
 */
object PolishHolidays {

    data class Holiday(val date: LocalDate, val name: String)

    private val FIXED: List<Triple<MonthDay, String, Int>> = listOf(
        Triple(MonthDay.of(1, 1), "Nowy Rok", 0),
        Triple(MonthDay.of(1, 6), "Święto Trzech Króli", 2011),
        Triple(MonthDay.of(5, 1), "Święto Pracy", 0),
        Triple(MonthDay.of(5, 3), "Święto Konstytucji 3 Maja", 0),
        Triple(MonthDay.of(8, 15), "Wniebowzięcie Najświętszej Maryi Panny", 0),
        Triple(MonthDay.of(11, 1), "Wszystkich Świętych", 0),
        Triple(MonthDay.of(11, 11), "Narodowe Święto Niepodległości", 0),
        Triple(MonthDay.of(12, 24), "Wigilia Bożego Narodzenia", 2025),
        Triple(MonthDay.of(12, 25), "Boże Narodzenie (pierwszy dzień)", 0),
        Triple(MonthDay.of(12, 26), "Boże Narodzenie (drugi dzień)", 0)
    )

    /** Wszystkie dni ustawowo wolne w roku [year], posortowane. */
    fun forYear(year: Int): List<Holiday> {
        val easter = easterSunday(year)
        val fixed = FIXED
            .filter { (_, _, since) -> year >= since }
            .map { (day, name, _) -> Holiday(day.atYear(year), name) }
        val movable = listOf(
            Holiday(easter, "Wielkanoc"),
            Holiday(easter.plusDays(1), "Poniedziałek Wielkanocny"),
            Holiday(easter.plusDays(49), "Zielone Świątki"),
            Holiday(easter.plusDays(60), "Boże Ciało")
        )
        return (fixed + movable).sortedBy { it.date }
    }

    fun isHoliday(date: LocalDate): Boolean = forYear(date.year).any { it.date == date }

    /**
     * Niedziela Wielkanocna w kalendarzu gregoriańskim — algorytm anonimowy
     * (Meeus/Jones/Butcher), poprawny dla każdego roku od 1583.
     */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    /** Czy [date] jest dniem roboczym: poniedziałek–piątek, który nie jest świętem. */
    fun isWorkingDay(date: LocalDate): Boolean =
        date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY && !isHoliday(date)

    /** Liczba dni roboczych w zakresie domkniętym [from]–[to]; 0, gdy zakres jest pusty. */
    fun workingDaysBetween(from: LocalDate, to: LocalDate): Int {
        if (to.isBefore(from)) return 0
        val holidays = (from.year..to.year).flatMap { forYear(it) }.map { it.date }.toSet()
        var count = 0
        var day = from
        while (!day.isAfter(to)) {
            if (day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY && day !in holidays) count++
            day = day.plusDays(1)
        }
        return count
    }

    /**
     * Święta w zakresie, które faktycznie skróciły urlop — wypadające w dzień powszedni.
     * Święto w sobotę czy niedzielę niczego nie zmienia w liczbie dni, więc na liście
     * przy liczniku byłoby tylko szumem.
     */
    fun weekdayHolidaysBetween(from: LocalDate, to: LocalDate): List<Holiday> {
        if (to.isBefore(from)) return emptyList()
        return (from.year..to.year)
            .flatMap { forYear(it) }
            .filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
            .filter { it.date.dayOfWeek != DayOfWeek.SATURDAY && it.date.dayOfWeek != DayOfWeek.SUNDAY }
    }
}
