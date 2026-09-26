package pl.detailing.crm.ownerreport.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

class ReportPeriodTest {

    private val wednesday = LocalDate.of(2026, 9, 23)

    @Test
    fun `tydzien to poniedzialek-niedziela, raport tylko za zakonczony`() {
        val period = ReportPeriod.latestFull(ReportLength.WEEK, wednesday)
        assertEquals(LocalDate.of(2026, 9, 14), period.from)
        assertEquals(LocalDate.of(2026, 9, 20), period.to)
        // W niedzielę bieżący tydzień jeszcze trwa.
        assertEquals(LocalDate.of(2026, 9, 7), ReportPeriod.latestFull(ReportLength.WEEK, LocalDate.of(2026, 9, 20)).from)
    }

    @Test
    fun `dwa tygodnie zaczynaja sie w poniedzialek od stalej kotwicy`() {
        val period = ReportPeriod.latestFull(ReportLength.TWO_WEEKS, wednesday)
        assertEquals(DayOfWeek.MONDAY, period.from.dayOfWeek)
        assertEquals(14, period.days)
        assertTrue(period.to.isBefore(wednesday))
        // Kolejne okresy stykają się bez przerw i nie nachodzą na siebie.
        assertEquals(period.from.minusDays(1), period.previous().to)
        // Podział nie zależy od roku (tydzień ISO 53 nie rozjeżdża okresów).
        val acrossYear = ReportPeriod.containing(ReportLength.TWO_WEEKS, LocalDate.of(2027, 1, 1))
        assertEquals(DayOfWeek.MONDAY, acrossYear.from.dayOfWeek)
        assertEquals(14, acrossYear.days)
    }

    @Test
    fun `miesiac kalendarzowy, poprzedni to poprzedni miesiac a nie 30 dni wstecz`() {
        val period = ReportPeriod.latestFull(ReportLength.MONTH, LocalDate.of(2026, 3, 5))
        assertEquals(LocalDate.of(2026, 2, 1), period.from)
        assertEquals(LocalDate.of(2026, 2, 28), period.to)
        assertEquals(LocalDate.of(2026, 1, 1), period.previous().from)
        assertEquals(LocalDate.of(2026, 1, 31), period.previous().to)
    }

    @Test
    fun `okres z adresu musi byc wyrownany i zakonczony`() {
        assertEquals(
            LocalDate.of(2026, 9, 20),
            ReportPeriod.fullStartingOn(ReportLength.WEEK, LocalDate.of(2026, 9, 14), wednesday)?.to
        )
        // Środa to nie początek tygodnia.
        assertNull(ReportPeriod.fullStartingOn(ReportLength.WEEK, LocalDate.of(2026, 9, 16), wednesday))
        // Bieżący tydzień jeszcze trwa.
        assertNull(ReportPeriod.fullStartingOn(ReportLength.WEEK, LocalDate.of(2026, 9, 21), wednesday))
        assertNull(ReportPeriod.fullStartingOn(ReportLength.MONTH, LocalDate.of(2026, 9, 1), wednesday))
    }

    @Test
    fun `lista do wyboru i okresy do mediany ida od najnowszego wstecz`() {
        val recent = ReportPeriod.recentFull(ReportLength.WEEK, wednesday, count = 3)
        assertEquals(listOf(14, 7, 31), recent.map { it.from.dayOfMonth })
        val median = recent.first().precedingPeriods(ReportPeriod.MEDIAN_PERIODS)
        assertEquals(ReportPeriod.MEDIAN_PERIODS, median.size)
        assertEquals(LocalDate.of(2026, 9, 7), median.first().from)
    }

    @Test
    fun `granice liczone o polnocy w Warszawie, nie w UTC`() {
        val period = ReportPeriod.containing(ReportLength.WEEK, LocalDate.of(2026, 9, 16))
        // CEST = UTC+2: niedziela 23:30 w Warszawie to jeszcze ten tydzień.
        assertEquals(Instant.parse("2026-09-13T22:00:00Z"), period.startInclusive)
        assertEquals(Instant.parse("2026-09-20T22:00:00Z"), period.endExclusive)
    }

    @Test
    fun `powiadomienie w dniu, w ktorym zaczyna sie nowy okres`() {
        val monday = LocalDate.of(2026, 9, 21)
        assertTrue(ReportFrequency.WEEKLY.isDueOn(monday))
        assertFalse(ReportFrequency.WEEKLY.isDueOn(monday.plusDays(1)))
        assertFalse(ReportFrequency.OFF.isDueOn(monday))
        // Dokładnie jeden z dwóch kolejnych poniedziałków.
        assertTrue(ReportFrequency.BIWEEKLY.isDueOn(monday) xor ReportFrequency.BIWEEKLY.isDueOn(monday.plusWeeks(1)))
        assertTrue(ReportFrequency.MONTHLY.isDueOn(LocalDate.of(2026, 10, 1)))
        assertFalse(ReportFrequency.MONTHLY.isDueOn(LocalDate.of(2026, 10, 2)))
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
}
