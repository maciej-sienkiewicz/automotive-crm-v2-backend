package pl.detailing.crm.subscription.it

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Zegar sterowany z testu. Terminy w rozliczeniach (koniec triala, okresu, karencji,
 * wygaśnięcie zamówienia) są liczone od `Clock`, więc test przesuwa czas zamiast podmieniać
 * daty w bazie — scenariusz „mija 7 dni karencji" czyta się jak historia, a nie jak UPDATE.
 *
 * Wątkobezpieczny: testy współbieżności czytają go z kilku wątków naraz.
 */
class MutableClock(start: Instant = DEFAULT_START) : Clock() {
    private val current = AtomicReference(start)

    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = current.get()

    fun set(instant: Instant) = current.set(instant)
    fun advance(duration: Duration): Instant = current.updateAndGet { it.plus(duration) }
    fun advanceDays(days: Long): Instant = advance(Duration.ofDays(days))

    companion object {
        /** Stały punkt startu: wynik testu nie zależy od dnia, w którym go uruchomiono. */
        val DEFAULT_START: Instant = Instant.parse("2026-10-01T10:00:00Z").truncatedTo(ChronoUnit.SECONDS)
    }
}
