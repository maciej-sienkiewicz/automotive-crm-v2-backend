package pl.detailing.crm.smscampaigns.thankyou.domain

import org.springframework.stereotype.Component
import pl.detailing.crm.communication.window.SendWindow
import java.time.Instant
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
 * Granice godzin nie są już własnością podziękowań: to ogólne okno komunikacji z klientem
 * ([SendWindow], domyślnie 12:00–18:00), które bramka wysyłkowa egzekwuje dla każdej
 * wiadomości. Tu zostaje to, co jest specyficzne dla podziękowania — zapas po wydaniu
 * pojazdu i rozstrzyganie propozycji z formularza — a termin liczony w tej klasie zawsze
 * mieści się w oknie, więc dispatcher podziękowań nigdy nie trafia do kolejki bramki.
 *
 * Godziny są lokalne dla studia ([zone]) — klient czyta SMS zegarkiem, nie w UTC.
 */
@Component
class ThankYouSmsWindow(private val window: SendWindow) {

    val zone: ZoneId get() = window.zone

    fun contains(instant: Instant): Boolean = window.contains(instant)

    /**
     * Ten sam moment, jeśli mieści się w oknie; w przeciwnym razie najbliższe otwarcie
     * okna — dziś albo jutro.
     */
    fun nextSlotFrom(instant: Instant): Instant = window.nextSlotFrom(instant)

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

    companion object {
        /**
         * Zapas między wydaniem pojazdu a wysyłką. Klient ma zdążyć odjechać spod bramy,
         * zanim dostanie podziękowanie.
         */
        const val LEAD_TIME_MINUTES: Long = 15
    }
}
