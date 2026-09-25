package pl.detailing.crm.ownerreport.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class ReportPeriodTest {

    @Test
    fun `w poniedzialek raport obejmuje tydzien zakonczony wczoraj`() {
        val monday = LocalDate.of(2026, 9, 21)
        val period = ReportPeriod.lastFullWeeks(1, monday)
        assertEquals(LocalDate.of(2026, 9, 14), period.from)
        assertEquals(LocalDate.of(2026, 9, 20), period.to)
        assertEquals(7, period.days)
    }

    @Test
    fun `w niedziele biezacy tydzien jeszcze trwa - raport bierze poprzedni`() {
        val sunday = LocalDate.of(2026, 9, 20)
        val period = ReportPeriod.lastFullWeeks(1, sunday)
        assertEquals(LocalDate.of(2026, 9, 7), period.from)
        assertEquals(LocalDate.of(2026, 9, 13), period.to)
    }

    @Test
    fun `dwutygodniowy obejmuje 14 dni, a poprzedni okres ma te sama dlugosc tuz przed nim`() {
        val period = ReportPeriod.lastFullWeeks(2, LocalDate.of(2026, 9, 21))
        assertEquals(LocalDate.of(2026, 9, 7), period.from)
        assertEquals(14, period.days)
        val previous = period.previous()
        assertEquals(LocalDate.of(2026, 8, 24), previous.from)
        assertEquals(LocalDate.of(2026, 9, 6), previous.to)
    }

    @Test
    fun `granice liczone o polnocy w Warszawie, nie w UTC`() {
        val period = ReportPeriod(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20))
        // CEST = UTC+2: niedziela 23:30 w Warszawie to jeszcze ten tydzień.
        assertEquals(Instant.parse("2026-09-13T22:00:00Z"), period.startInclusive)
        assertEquals(Instant.parse("2026-09-20T22:00:00Z"), period.endExclusive)
    }

    @Test
    fun `tygodniowy wychodzi w kazdy poniedzialek, dwutygodniowy w parzyste tygodnie ISO`() {
        val monday = LocalDate.of(2026, 9, 21)
        val nextMonday = monday.plusWeeks(1)
        assertTrue(ReportFrequency.WEEKLY.isDueOn(monday))
        assertTrue(ReportFrequency.WEEKLY.isDueOn(nextMonday))
        assertFalse(ReportFrequency.WEEKLY.isDueOn(monday.plusDays(1)))
        assertFalse(ReportFrequency.OFF.isDueOn(monday))
        // Dokładnie jeden z dwóch kolejnych poniedziałków.
        assertTrue(ReportFrequency.BIWEEKLY.isDueOn(monday) xor ReportFrequency.BIWEEKLY.isDueOn(nextMonday))
    }

    @Test
    fun `mediana czasu odpowiedzi pomija zapytania bez odpowiedzi, ale liczy je w turach`() {
        val replies = ReplyTimes.of(listOf(10L, null, 90L, 30L, null))
        assertEquals(5, replies.inquiries)
        assertEquals(3, replies.answered)
        assertEquals(2, replies.unanswered)
        assertEquals(2, replies.answeredWithinHour)
        assertEquals(30L, replies.medianMinutes)

        assertEquals(20L, ReplyTimes.of(listOf(10L, 30L)).medianMinutes)
        assertNull(ReplyTimes.of(listOf(null)).medianMinutes)
    }

    @Test
    fun `srednia wartosc wizyty zaokragla do grosza, bez wizyt jej nie ma`() {
        assertEquals(66667L, ClosedVisits(count = 3, grossCents = 200000, netCents = 162602).averageGrossCents)
        assertEquals(190000L, ClosedVisits(count = 1, grossCents = 190000, netCents = 154472).averageGrossCents)
        assertNull(ClosedVisits(count = 0, grossCents = 0, netCents = 0).averageGrossCents)
    }
}
