package pl.detailing.crm.employee.leaverequest.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Dni robocze są drukowane na wniosku i zamrażane — pomyłka w Wielkanocy albo Bożym
 * Ciele to dzień urlopu za dużo albo za mało na dokumencie z podpisami obu stron.
 */
class PolishHolidaysTest {

    @ParameterizedTest(name = "Wielkanoc {0} = {1}")
    @CsvSource(
        "2019, 2019-04-21",
        "2024, 2024-03-31",
        "2025, 2025-04-20",
        "2026, 2026-04-05",
        "2027, 2027-03-28",
        "2038, 2038-04-25"
    )
    fun `easter sunday matches the calendar`(year: Int, expected: String) {
        assertEquals(LocalDate.parse(expected), PolishHolidays.easterSunday(year))
    }

    @ParameterizedTest(name = "Boże Ciało {0} = {1}")
    @CsvSource(
        "2024, 2024-05-30",
        "2025, 2025-06-19",
        "2026, 2026-06-04",
        "2027, 2027-05-27"
    )
    fun `corpus christi is sixty days after easter`(year: Int, expected: String) {
        val holiday = PolishHolidays.forYear(year).single { it.name == "Boże Ciało" }
        assertEquals(LocalDate.parse(expected), holiday.date)
        assertEquals(java.time.DayOfWeek.THURSDAY, holiday.date.dayOfWeek)
    }

    @Test
    fun `easter week 2026 loses easter monday`() {
        // 30.03–10.04.2026: dwa tygodnie pn–pt = 10 dni, minus Poniedziałek Wielkanocny 06.04.
        assertEquals(9, PolishHolidays.workingDaysBetween(LocalDate.of(2026, 3, 30), LocalDate.of(2026, 4, 10)))
    }

    @Test
    fun `corpus christi week 2026 has four working days`() {
        assertEquals(4, PolishHolidays.workingDaysBetween(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5)))
        val skipped = PolishHolidays.weekdayHolidaysBetween(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7))
        assertEquals(listOf("Boże Ciało"), skipped.map { it.name })
    }

    @Test
    fun `christmas eve is a public holiday from 2025 only`() {
        assertTrue(PolishHolidays.isHoliday(LocalDate.of(2025, 12, 24)))
        assertTrue(PolishHolidays.isHoliday(LocalDate.of(2026, 12, 24)))
        assertFalse(PolishHolidays.isHoliday(LocalDate.of(2024, 12, 24)))
        // 21–31.12.2026: pn–pt to 21–25 i 28–31 (9 dni) minus 24, 25 → 7.
        assertEquals(7, PolishHolidays.workingDaysBetween(LocalDate.of(2026, 12, 21), LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `may weekend and november`() {
        // 01.05.2026 (pt) i 03.05 (nd): z tygodnia 27.04–01.05 zostają 4 dni.
        assertEquals(4, PolishHolidays.workingDaysBetween(LocalDate.of(2026, 4, 27), LocalDate.of(2026, 5, 1)))
        // 11.11.2026 to środa.
        assertEquals(4, PolishHolidays.workingDaysBetween(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 13)))
    }

    @Test
    fun `weekend-only range has no working days and holidays on weekends are not listed`() {
        assertEquals(0, PolishHolidays.workingDaysBetween(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 4)))
        // Zielone Świątki zawsze w niedzielę — nie skracają urlopu, więc ich nie wypisujemy.
        assertTrue(PolishHolidays.weekdayHolidaysBetween(LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 24)).isEmpty())
    }

    @Test
    fun `range across new year counts both years`() {
        // 29.12.2025–02.01.2026: 29, 30, 31.12 i 02.01 (01.01 święto) → 4.
        assertEquals(4, PolishHolidays.workingDaysBetween(LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 2)))
    }
}
