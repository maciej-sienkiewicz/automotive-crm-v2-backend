package pl.detailing.crm.instagram.ads

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Reguła liczenia dni sponsorowanych jest ustaleniem produktowym, nie detalem
 * implementacji: właściciel liczy dni, w których konkurent był widoczny, więc
 * cztery kampanie od 14 do 16 marca to 4 × 3 = 12 dni, a nie 3.
 *
 * Testy pilnują tej reguły oraz dwóch rzeczy, które łatwo popsuć po cichu:
 * przycięcia kampanii do roku kalendarza i rozdzielenia równoległych kampanii
 * na osobne tory — bo to one pokazują podwójne liczenie na ekranie.
 */
class AdCalendarMathTest {

    private val year2026Start = LocalDate.of(2026, 1, 1)
    private val today = LocalDate.of(2026, 9, 6)

    @Test
    fun `kampania od 14 do 16 marca to trzy dni, licząc oba końce`() {
        val days = AdCalendarMath.daysInWindow(
            start = LocalDate.of(2026, 3, 14),
            stop = LocalDate.of(2026, 3, 16),
            windowStart = year2026Start,
            windowEnd = today
        )
        assertEquals(3, days)
    }

    @Test
    fun `cztery kampanie w tych samych trzech dniach dają dwanascie dni sponsorowanych`() {
        val campaign = LocalDate.of(2026, 3, 14) to LocalDate.of(2026, 3, 16)
        val total = (1..4).sumOf {
            AdCalendarMath.daysInWindow(campaign.first, campaign.second, year2026Start, today)
        }
        assertEquals(12, total)
    }

    @Test
    fun `kampania jednodniowa to jeden dzień, nie zero`() {
        val day = LocalDate.of(2026, 5, 10)
        assertEquals(1, AdCalendarMath.daysInWindow(day, day, year2026Start, today))
    }

    @Test
    fun `trwająca kampania liczy się do dziś, a nie do konca roku`() {
        val days = AdCalendarMath.daysInWindow(
            start = LocalDate.of(2026, 9, 1),
            stop = null,
            windowStart = year2026Start,
            windowEnd = today
        )
        assertEquals(6, days)
    }

    @Test
    fun `kampania zaczęta w poprzednim roku liczy się tylko od pierwszego stycznia`() {
        val days = AdCalendarMath.daysInWindow(
            start = LocalDate.of(2025, 12, 20),
            stop = LocalDate.of(2026, 1, 10),
            windowStart = year2026Start,
            windowEnd = today
        )
        assertEquals(10, days)
    }

    @Test
    fun `kampania zamknięta przed oknem nie wnosi ani jednego dnia`() {
        val days = AdCalendarMath.daysInWindow(
            start = LocalDate.of(2025, 3, 1),
            stop = LocalDate.of(2025, 4, 1),
            windowStart = year2026Start,
            windowEnd = today
        )
        assertEquals(0, days)
    }

    @Test
    fun `kampanie rozłączne dzielą jeden tor`() {
        val lanes = AdCalendarMath.assignLanes(
            listOf(
                LocalDate.of(2026, 1, 1) to LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 3, 1) to LocalDate.of(2026, 3, 31)
            )
        )
        assertEquals(listOf(0, 0), lanes)
    }

    @Test
    fun `kampanie nachodzące na siebie dostają osobne tory`() {
        val lanes = AdCalendarMath.assignLanes(
            listOf(
                LocalDate.of(2026, 3, 14) to LocalDate.of(2026, 3, 16),
                LocalDate.of(2026, 3, 14) to LocalDate.of(2026, 3, 16),
                LocalDate.of(2026, 3, 15) to LocalDate.of(2026, 3, 20)
            )
        )
        assertEquals(3, lanes.toSet().size)
    }

    @Test
    fun `kampania kończąca się w dniu startu następnej nie dzieli z nią toru`() {
        val lanes = AdCalendarMath.assignLanes(
            listOf(
                LocalDate.of(2026, 3, 1) to LocalDate.of(2026, 3, 10),
                LocalDate.of(2026, 3, 10) to LocalDate.of(2026, 3, 20)
            )
        )
        assertEquals(listOf(0, 1), lanes)
    }

    @Test
    fun `wiek 25-54 obejmuje przedziały Meta od 25-34 do 45-54`() {
        listOf("25-34", "35-44", "45-54").forEach { bucket ->
            assertTrue(AdCalendarMath.inTargetAge(bucket, "25-54"), bucket)
        }
        listOf("13-17", "18-24", "55-64", "65+").forEach { bucket ->
            assertFalse(AdCalendarMath.inTargetAge(bucket, "25-54"), bucket)
        }
    }

    @Test
    fun `wiek otwarty 18-65+ obejmuje takze przedział 65 plus`() {
        assertTrue(AdCalendarMath.inTargetAge("65+", "18-65+"))
        assertFalse(AdCalendarMath.inTargetAge("13-17", "18-65+"))
    }

    @Test
    fun `nieznane ustawienie wieku nie przygasza wszystkiego`() {
        assertTrue(AdCalendarMath.inTargetAge("25-34", null))
        assertTrue(AdCalendarMath.inTargetAge("25-34", ""))
    }
}
