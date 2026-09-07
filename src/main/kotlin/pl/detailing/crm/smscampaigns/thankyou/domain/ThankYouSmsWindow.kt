package pl.detailing.crm.smscampaigns.thankyou.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Pora dnia, o której wypada podziękować klientowi za wizytę.
 *
 * Podziękowanie było dotąd doklejone do momentu zamknięcia wizyty w systemie, a studio
 * zamyka wizyty wtedy, kiedy ma chwilę — często długo po wydaniu auta. Klienci dostawali
 * przez to „dziękujemy za wizytę" o 20:50, czyli o porze, o której nikt nie chce dostać
 * wiadomości od warsztatu.
 *
 * Okno [OPENS_AT]–[CLOSES_AT] jest granicą twardą: wysyłka nigdy nie wypada poza nim,
 * niezależnie od tego, co przyszło z formularza. Poza oknem przesuwamy na najbliższą
 * dozwoloną godzinę, czyli dziś w południe (jeśli jest jeszcze przed) albo jutro
 * w południe (jeśli jest już po).
 *
 * Godziny są lokalne dla studia ([ZONE]) — klient czyta SMS zegarkiem, nie w UTC.
 */
object ThankYouSmsWindow {

    val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")

    /** Pierwsza dozwolona godzina wysyłki (włącznie). */
    val OPENS_AT: LocalTime = LocalTime.of(12, 0)

    /** Ostatnia dozwolona godzina wysyłki (włącznie). */
    val CLOSES_AT: LocalTime = LocalTime.of(18, 0)

    /**
     * Zapas między wydaniem pojazdu a wysyłką. Klient ma zdążyć odjechać spod bramy,
     * zanim dostanie podziękowanie.
     */
    const val LEAD_TIME_MINUTES: Long = 15

    fun contains(instant: Instant): Boolean {
        val time = instant.atZone(ZONE).toLocalTime()
        return !time.isBefore(OPENS_AT) && !time.isAfter(CLOSES_AT)
    }

    /**
     * Ten sam moment, jeśli mieści się w oknie; w przeciwnym razie najbliższe otwarcie
     * okna — dziś albo jutro w [OPENS_AT].
     */
    fun nextSlotFrom(instant: Instant): Instant {
        val local = instant.atZone(ZONE)
        val time = local.toLocalTime()
        return when {
            time.isBefore(OPENS_AT) -> local.with(OPENS_AT)
            time.isAfter(CLOSES_AT) -> local.plusDays(1).with(OPENS_AT)
            else -> local
        }.toInstant()
    }

    /** Propozycja pokazywana użytkownikowi: „teraz + 15 minut", dociągnięta do okna. */
    fun defaultFor(now: Instant): Instant =
        nextSlotFrom(now.plus(LEAD_TIME_MINUTES, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES))

    /**
     * Termin, o którym decyduje serwer.
     *
     * Formularz jest tylko propozycją: termin z przeszłości (okno wisiało otwarte przez
     * pół dnia) i termin spoza okna (ręcznie wpisana 22:00) wracają na najbliższą
     * dozwoloną godzinę, zamiast zostać wysłane wtedy, o co prosi przeglądarka.
     */
    fun resolveSendAt(requested: Instant?, now: Instant): Instant {
        val candidate = requested?.takeIf { it.isAfter(now) } ?: return defaultFor(now)
        return nextSlotFrom(candidate.truncatedTo(ChronoUnit.MINUTES))
    }
}
