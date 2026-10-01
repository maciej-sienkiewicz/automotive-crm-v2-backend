package pl.detailing.crm.worktime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.employee.leave.domain.LeaveType
import pl.detailing.crm.worktime.infrastructure.WorkTimeEntryEntity
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Norma i braki karty. Liczy się to, co pracownik i menedżer zobaczą jako „brakuje 3 dni"
 * albo „norma 120 h": święto ani urlop nie może być brakiem, a dzień jutrzejszy też nie.
 */
class WorkTimeCalendarTest {

    private val user = UUID.randomUUID()
    private val studio = UUID.randomUUID()

    private fun entry(date: LocalDate, minutes: Int = 480) =
        WorkTimeEntryEntity(userId = user, studioId = studio, date = date, minutes = minutes)

    @Test
    fun `norma odejmuje swieta i dni robocze urlopu`() {
        // Listopad 2026: 21 dni pon–pt, z czego 11.11 (śr) to święto → 20 dni roboczych.
        // 1.11 (Wszystkich Świętych) wypada w niedzielę i niczego nie odejmuje.
        val november = YearMonth.of(2026, 11)
        // Urlop 2–8.11 (pn–nd): 5 dni roboczych, weekend się nie liczy.
        val leave = WorkTimeCalendar.leaveByDay(
            november,
            listOf(
                pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveEntity(
                    id = UUID.randomUUID(), studioId = studio, employeeId = UUID.randomUUID(), leaveType = LeaveType.ANNUAL,
                    startDate = november.atDay(2), endDate = november.atDay(8), note = null, createdBy = UUID.randomUUID()
                )
            )
        )

        val month = WorkTimeCalendar.month(november, emptyList(), leave, today = LocalDate.of(2026, 10, 1))

        assertEquals(20, month.workingDays)
        assertEquals(5, month.leaveWorkingDays)
        assertEquals((20 - 5) * 480, month.expectedMinutes)
        val armistice = month.days.single { it.date == november.atDay(11) }
        assertEquals("Narodowe Święto Niepodległości", armistice.holidayName)
        assertTrue(!armistice.isWorkingDay)
        assertNull(month.days.single { it.date == november.atDay(7) }.leave, "Sobota w środku urlopu nie jest dniem urlopu na karcie")
        assertEquals(LeaveType.ANNUAL, month.days.single { it.date == november.atDay(2) }.leave)
    }

    @Test
    fun `brakujace dni w biezacym miesiacu licza sie tylko do dzis`() {
        // Wrzesień 2026, dziś wtorek 15.09: dni robocze 1–4, 7–11, 14–15 = 11.
        val september = YearMonth.of(2026, 9)
        val today = LocalDate.of(2026, 9, 15)
        val sick = mapOf(september.atDay(3) to LeaveType.SICK)

        val month = WorkTimeCalendar.month(
            september, listOf(entry(september.atDay(1)), entry(september.atDay(2), 600)), sick, today
        )

        // 11 dni roboczych do dziś − 2 z wpisem − 1 na L4 = 8.
        assertEquals(8, month.missingWorkingDays)
        assertTrue(month.days.filter { it.date.isAfter(today) }.none { it.missing }, "Jutro nie jest brakiem")
        assertEquals(1080, month.totalMinutes)
        assertEquals(120, month.overtimeMinutes)
        assertEquals("L4", WorkTimeCalendar.leaveLabel(LeaveType.SICK))
    }

    @Test
    fun `miesiac przeszly brakuje wszystkich dni roboczych, przyszly zadnego`() {
        val today = LocalDate.of(2026, 9, 15)
        // Sierpień 2026: 21 dni pon–pt; 15.08 wypada w sobotę.
        assertEquals(21, WorkTimeCalendar.month(YearMonth.of(2026, 8), emptyList(), emptyMap(), today).missingWorkingDays)
        assertEquals(0, WorkTimeCalendar.month(YearMonth.of(2026, 10), emptyList(), emptyMap(), today).missingWorkingDays)
    }

    @Test
    fun `swieto nie jest brakiem nawet bez wpisu`() {
        val november = YearMonth.of(2026, 11)
        val month = WorkTimeCalendar.month(november, emptyList(), emptyMap(), today = LocalDate.of(2026, 12, 1))

        assertEquals(20, month.missingWorkingDays)
        assertTrue(month.days.single { it.date == november.atDay(11) }.missing.not())
    }
}
