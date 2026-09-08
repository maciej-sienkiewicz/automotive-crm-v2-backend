package pl.detailing.crm.smscampaigns.thankyou

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.communication.window.SendWindow
import pl.detailing.crm.smscampaigns.thankyou.domain.ThankYouSmsWindow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Godziny, o których wolno podziękować klientowi.
 *
 * Studio zamyka wizyty wtedy, kiedy ma chwilę — czasem o 20:50, długo po wydaniu auta.
 * Klient nie może przez to dostać „dziękujemy za wizytę" na dobranoc, więc okno
 * 12:00–18:00 jest granicą twardą, a nie sugestią.
 */
class ThankYouSmsWindowTest {

    private val window = ThankYouSmsWindow(SendWindow.DEFAULT)

    private fun at(hour: Int, minute: Int, day: Int = 15) =
        LocalDateTime.of(LocalDate.of(2026, 9, day), LocalTime.of(hour, minute))
            .atZone(window.zone)
            .toInstant()

    private fun localOf(instant: java.time.Instant) =
        instant.atZone(window.zone).toLocalDateTime()

    // ── Propozycja pokazywana w formularzu ───────────────────────────────────

    @Test
    fun `w srodku dnia proponuje kwadrans od teraz`() {
        assertEquals(localOf(at(15, 15)), localOf(window.defaultFor(at(15, 0))))
    }

    @Test
    fun `kwadrans po 17 45 nadal miesci sie w oknie`() {
        assertEquals(localOf(at(18, 0)), localOf(window.defaultFor(at(17, 45))))
    }

    @Test
    fun `kwadrans po 17 50 wypada za oknem, wiec przechodzi na jutrzejsze poludnie`() {
        assertEquals(localOf(at(12, 0, day = 16)), localOf(window.defaultFor(at(17, 50))))
    }

    @Test
    fun `wydanie o 20 50 nie budzi klienta wieczorem`() {
        val proposal = window.defaultFor(at(20, 50))

        assertEquals(localOf(at(12, 0, day = 16)), localOf(proposal))
        assertTrue(window.contains(proposal))
    }

    @Test
    fun `przed poludniem czeka do otwarcia okna tego samego dnia`() {
        assertEquals(localOf(at(12, 0)), localOf(window.defaultFor(at(8, 30))))
    }

    @Test
    fun `kwadrans przed poludniem jeszcze nie otwiera okna`() {
        // 11:50 + 15 min = 12:05 — mieści się, więc nie ma czego przesuwać.
        assertEquals(localOf(at(12, 5)), localOf(window.defaultFor(at(11, 50))))
    }

    // ── Granice okna ─────────────────────────────────────────────────────────

    @Test
    fun `granice okna naleza do okna`() {
        assertTrue(window.contains(at(12, 0)))
        assertTrue(window.contains(at(18, 0)))
    }

    @Test
    fun `minuta przed i minuta po granicy juz nie`() {
        assertFalse(window.contains(at(11, 59)))
        assertFalse(window.contains(at(18, 1)))
    }

    // ── Termin rozstrzygany po stronie serwera ───────────────────────────────

    @Test
    fun `termin z formularza mieszczacy sie w oknie zostaje przyjety`() {
        assertEquals(
            localOf(at(16, 30)),
            localOf(window.resolveSendAt(requested = at(16, 30), now = at(13, 0)))
        )
    }

    @Test
    fun `termin recznie ustawiony na 22 00 wraca na najblizsze poludnie`() {
        assertEquals(
            localOf(at(12, 0, day = 16)),
            localOf(window.resolveSendAt(requested = at(22, 0), now = at(13, 0)))
        )
    }

    @Test
    fun `termin z przeszlosci ustepuje domyslnej propozycji`() {
        // Okno wydania stało otwarte pół dnia: godzina z formularza dawno minęła.
        assertEquals(
            localOf(at(16, 15)),
            localOf(window.resolveSendAt(requested = at(13, 0), now = at(16, 0)))
        )
    }

    @Test
    fun `brak terminu w zadaniu oznacza najblizszy dozwolony`() {
        assertEquals(
            localOf(at(12, 0, day = 16)),
            localOf(window.resolveSendAt(requested = null, now = at(19, 30)))
        )
    }

    @Test
    fun `zaden rozstrzygniety termin nie wypada poza oknem`() {
        val everyQuarterOfADay = (0 until 24 * 4).map { at(it / 4, (it % 4) * 15) }

        everyQuarterOfADay.forEach { now ->
            assertTrue(
                window.contains(window.defaultFor(now)),
                "propozycja dla $now wypadła poza oknem"
            )
            assertTrue(
                window.contains(window.resolveSendAt(now.plusSeconds(3600), now)),
                "termin z formularza dla $now wypadł poza oknem"
            )
        }
    }
}
