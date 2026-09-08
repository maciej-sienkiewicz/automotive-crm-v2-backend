package pl.detailing.crm.communication.window

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Godziny, w których wolno pisać do klienta — wspólne dla całej komunikacji.
 *
 * Granice są włącznie i liczone z dokładnością do minuty: dispatcher odpalany o pełnej
 * minucie musi jeszcze zdążyć z ostatnim slotem (18:00:xx), a 18:01 to już po godzinach.
 */
class SendWindowTest {

    private val window = SendWindow.DEFAULT

    private fun at(hour: Int, minute: Int, second: Int = 0, day: Int = 15): Instant =
        LocalDateTime.of(LocalDate.of(2026, 9, day), LocalTime.of(hour, minute, second))
            .atZone(window.zone)
            .toInstant()

    private fun localOf(instant: Instant) = instant.atZone(window.zone).toLocalDateTime()

    // ── contains ─────────────────────────────────────────────────────────────

    @Test
    fun `poludnie i osiemnasta sa w oknie, granice wlacznie`() {
        assertTrue(window.contains(at(12, 0)))
        assertTrue(window.contains(at(15, 30)))
        assertTrue(window.contains(at(18, 0)))
    }

    @Test
    fun `sekundy po osiemnastej nadal licza sie jako osiemnasta`() {
        assertTrue(window.contains(at(18, 0, second = 45)))
        assertFalse(window.contains(at(18, 1)))
    }

    @Test
    fun `minuta przed poludniem i wieczor sa poza oknem`() {
        assertFalse(window.contains(at(11, 59, second = 59)))
        assertFalse(window.contains(at(20, 50)))
        assertFalse(window.contains(at(7, 0)))
    }

    // ── nextSlotFrom ─────────────────────────────────────────────────────────

    @Test
    fun `w oknie zwraca ten sam moment`() {
        assertEquals(at(14, 7), window.nextSlotFrom(at(14, 7)))
    }

    @Test
    fun `przed otwarciem przesuwa na dzisiejsze poludnie`() {
        assertEquals(localOf(at(12, 0)), localOf(window.nextSlotFrom(at(8, 30))))
    }

    @Test
    fun `po zamknieciu przesuwa na jutrzejsze poludnie`() {
        assertEquals(localOf(at(12, 0, day = 16)), localOf(window.nextSlotFrom(at(20, 50))))
        assertEquals(localOf(at(12, 0, day = 16)), localOf(window.nextSlotFrom(at(18, 1))))
    }

    @Test
    fun `okno liczy czas lokalny studia, nie UTC`() {
        // 11:30 UTC w polskie lato to 13:30 w Warszawie — czyli w oknie.
        val utc = LocalDateTime.of(2026, 7, 15, 11, 30).atZone(ZoneId.of("UTC")).toInstant()
        assertTrue(window.contains(utc))
        // 17:30 UTC = 19:30 w Warszawie — już po godzinach, chociaż w UTC jeszcze „w oknie".
        val utcEvening = LocalDateTime.of(2026, 7, 15, 17, 30).atZone(ZoneId.of("UTC")).toInstant()
        assertFalse(window.contains(utcEvening))
    }

    // ── enabled = false ──────────────────────────────────────────────────────

    @Test
    fun `wylaczone okno przepuszcza wszystko i niczego nie przesuwa`() {
        val open = SendWindow.ALWAYS_OPEN
        assertTrue(open.contains(at(3, 0)))
        assertEquals(at(3, 0), open.nextSlotFrom(at(3, 0)))
    }

    @Test
    fun `okno zamykajace sie przed otwarciem jest bledem konfiguracji`() {
        assertThrows(IllegalArgumentException::class.java) {
            SendWindow(ZoneId.of("Europe/Warsaw"), LocalTime.of(18, 0), LocalTime.of(12, 0))
        }
    }
}
