package pl.detailing.crm.communication.window

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Godziny, w których wolno odezwać się do klienta.
 *
 * Reguła wzięła się z podziękowań po wizycie: studio zamyka wizyty wtedy, kiedy ma
 * chwilę — często o 20:50 — i klient dostawał „dziękujemy" na dobranoc. Ta sama pora
 * jest równie zła dla potwierdzenia rezerwacji złożonej o 23:00 czy dla kampanii
 * odpalonej przed otwarciem warsztatu, więc okno obowiązuje CAŁĄ komunikację do klienta,
 * a nie jeden rodzaj wiadomości.
 *
 * Okno [opensAt]–[closesAt] jest granicą twardą: wiadomość poza nim nie wychodzi, tylko
 * czeka na najbliższe otwarcie — dziś (jeśli jeszcze przed) albo jutro (jeśli już po).
 * Wyjątkiem są wiadomości, które przez czekanie tracą sens (link do podpisu dla klienta
 * stojącego przy ladzie, przypomnienie „za godzinę wizyta") — o nich decyduje wywołujący
 * przez [pl.detailing.crm.communication.DeliveryPolicy.IMMEDIATE].
 *
 * Godziny są lokalne dla studia ([zone]) — klient czyta SMS zegarkiem, nie w UTC.
 * Granice porównujemy z dokładnością do minuty: 18:00:45 to nadal „osiemnasta", więc
 * dispatcher odpalany co pełną minutę nie przegapi ostatniego slotu okna.
 *
 * [enabled] = false wyłącza regułę w całości (środowiska testowe, rehearsal na żywo):
 * każdy moment mieści się wtedy w oknie i nic nie jest odkładane.
 */
data class SendWindow(
    val zone: ZoneId,
    /** Pierwsza dozwolona minuta wysyłki (włącznie). */
    val opensAt: LocalTime,
    /** Ostatnia dozwolona minuta wysyłki (włącznie). */
    val closesAt: LocalTime,
    val enabled: Boolean = true
) {
    init {
        require(!closesAt.isBefore(opensAt)) {
            "Okno wysyłki musi się zamykać po otwarciu (opensAt=$opensAt, closesAt=$closesAt)"
        }
    }

    fun contains(instant: Instant): Boolean {
        if (!enabled) return true
        val time = instant.atZone(zone).toLocalTime().truncatedTo(ChronoUnit.MINUTES)
        return !time.isBefore(opensAt) && !time.isAfter(closesAt)
    }

    /**
     * Ten sam moment, jeśli mieści się w oknie; w przeciwnym razie najbliższe otwarcie
     * okna — dziś albo jutro o [opensAt].
     */
    fun nextSlotFrom(instant: Instant): Instant {
        if (!enabled) return instant
        val local: ZonedDateTime = instant.atZone(zone)
        val time = local.toLocalTime().truncatedTo(ChronoUnit.MINUTES)
        return when {
            time.isBefore(opensAt) -> local.with(opensAt).truncatedTo(ChronoUnit.MINUTES)
            time.isAfter(closesAt) -> local.plusDays(1).with(opensAt).truncatedTo(ChronoUnit.MINUTES)
            else -> local
        }.toInstant()
    }

    companion object {
        /** 12:00–18:00 czasu warszawskiego — wartość, od której zaczęły podziękowania po wizycie. */
        val DEFAULT = SendWindow(
            zone = ZoneId.of("Europe/Warsaw"),
            opensAt = LocalTime.of(12, 0),
            closesAt = LocalTime.of(18, 0)
        )

        /** Brak ograniczeń — każda pora jest dobra. Do testów i środowisk bez reguły. */
        val ALWAYS_OPEN = DEFAULT.copy(enabled = false)
    }
}
