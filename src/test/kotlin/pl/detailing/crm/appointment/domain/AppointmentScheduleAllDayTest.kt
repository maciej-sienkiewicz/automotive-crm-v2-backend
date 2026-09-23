package pl.detailing.crm.appointment.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Wizyta całodniowa trwa jeden dzień - wizyta na kilka dni ma zawsze godziny. Rezerwacja
 * całodniowa z 24.09, przeciągnięta na ekranie edycji do 28.09 23:45, zapisywała się jako
 * całodniowa na pięć dni (ekran nie ma przełącznika i odsyłał flagę oryginału).
 */
class AppointmentScheduleAllDayTest {

    private fun at(iso: String) = Instant.parse(iso)

    @Test
    fun `caly dzien czasu polskiego zostaje calodniowy`() {
        // 24.09 00:00 - 23:59:59 czasu letniego to 23.09 22:00Z - 24.09 21:59:59Z
        assertTrue(AppointmentSchedule.resolveAllDay(true, at("2026-09-23T22:00:00Z"), at("2026-09-24T21:59:59Z")))
    }

    @Test
    fun `caly dzien czasu zimowego zostaje calodniowy`() {
        // 5.03 00:00 - 23:59:59 czasu zimowego to 4.03 23:00Z - 5.03 22:59:59Z
        assertTrue(AppointmentSchedule.resolveAllDay(true, at("2026-03-04T23:00:00Z"), at("2026-03-05T22:59:59Z")))
    }

    @Test
    fun `wizyta na kilka dni nie jest calodniowa - rezerwacja LIDL 24-28_09`() {
        assertFalse(AppointmentSchedule.resolveAllDay(true, at("2026-09-23T22:00:00Z"), at("2026-09-28T21:45:00Z")))
    }

    @Test
    fun `dzien liczy sie w czasie polskim, nie w UTC`() {
        // W UTC to jeden dzień (5.03), w Polsce 5.03 01:00 - 6.03 00:59:59 - dwa dni.
        assertFalse(AppointmentSchedule.resolveAllDay(true, at("2026-03-05T00:00:00Z"), at("2026-03-05T23:59:59Z")))
    }

    @Test
    fun `wizyta z godzinami zostaje z godzinami`() {
        assertFalse(AppointmentSchedule.resolveAllDay(false, at("2026-09-24T07:00:00Z"), at("2026-09-24T09:00:00Z")))
    }

    @Test
    fun `of zdejmuje flage z terminu wielodniowego i zostawia godziny bez zmian`() {
        val schedule = AppointmentSchedule.of(true, at("2026-09-23T22:00:00Z"), at("2026-09-28T21:45:00Z"))

        assertEquals(
            AppointmentSchedule(isAllDay = false, startDateTime = at("2026-09-23T22:00:00Z"), endDateTime = at("2026-09-28T21:45:00Z")),
            schedule,
        )
    }
}
