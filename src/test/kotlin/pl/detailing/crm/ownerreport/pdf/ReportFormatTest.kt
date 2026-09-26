package pl.detailing.crm.ownerreport.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ReportFormatTest {

    @Test
    fun `kwota co do grosza z separatorem tysiecy`() {
        assertEquals("1 900,00 zł", ReportFormat.money(190000))
        assertEquals("1 234 567,89 zł", ReportFormat.money(123456789))
        assertEquals("0,05 zł", ReportFormat.money(5))
        assertEquals("-12,30 zł", ReportFormat.money(-1230))
    }

    @Test
    fun `zmiana wzgledem poprzedniego okresu`() {
        assertEquals("+18%", ReportFormat.change(118, 100))
        assertEquals("-25%", ReportFormat.change(75, 100))
        assertEquals("bez zmian", ReportFormat.change(5, 5))
        assertEquals("—", ReportFormat.change(3, 0))
        // Przy małych liczbach różnica w sztukach, nie procent.
        assertEquals("+1", ReportFormat.change(3, 2, smallCountThreshold = 10))
        assertEquals("-2", ReportFormat.change(4, 6, smallCountThreshold = 10))
        assertEquals("+<1%", ReportFormat.change(100001, 100000))
    }

    @Test
    fun `czas odpowiedzi po ludzku`() {
        assertEquals("45 min", ReportFormat.duration(45))
        assertEquals("2 h", ReportFormat.duration(120))
        assertEquals("1 h 20 min", ReportFormat.duration(80))
        assertEquals("1 dzień", ReportFormat.duration(24 * 60))
        assertEquals("2 dni 3 h", ReportFormat.duration(2 * 24 * 60 + 3 * 60 + 15))
    }

    @Test
    fun `procent i okres`() {
        assertEquals("33%", ReportFormat.percent(1, 3))
        assertEquals("—", ReportFormat.percent(0, 0))
        assertEquals("14.09–20.09.2026", ReportFormat.range(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20)))
        assertEquals("29.12.2025–04.01.2026", ReportFormat.range(LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 4)))
    }
}
