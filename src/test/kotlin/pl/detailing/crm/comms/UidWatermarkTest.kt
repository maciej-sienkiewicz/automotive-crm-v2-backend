package pl.detailing.crm.comms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.comms.engine.UidWatermark

/**
 * Znacznik UID jest jedyną pamięcią o tym, co już pobraliśmy z folderu — delta czyta
 * wyłącznie UID-y wyższe od niego. Przesunięcie go ponad wiadomość, której NIE udało
 * się zaimportować, znaczy „ta wiadomość przepadła na zawsze", i właśnie tak ginęły
 * odpowiedzi wysłane z Outlooka czy telefonu.
 */
class UidWatermarkTest {

    @Test
    fun `pusty przebieg zostawia znacznik tam, gdzie był`() {
        val watermark = UidWatermark(100L)

        assertEquals(100L, watermark.value())
    }

    @Test
    fun `udane wiadomości przesuwają znacznik na najwyższy UID`() {
        val watermark = UidWatermark(100L)

        watermark.done(101L)
        watermark.done(102L)

        assertEquals(102L, watermark.value())
    }

    @Test
    fun `nieudana wiadomość zatrzymuje znacznik tuż przed sobą`() {
        val watermark = UidWatermark(100L)

        watermark.done(101L)
        watermark.failed(102L)

        assertEquals(101L, watermark.value())
    }

    /**
     * Awaria jednej wiadomości nie może zablokować importu pozostałych, ale nie może
     * też ich „potwierdzić": znacznik cofa się do luki, więc kolejny przebieg pobierze
     * wszystko od niej w górę. Powtórki odsiewa deduplikacja po Message-ID.
     */
    @Test
    fun `późniejsze udane wiadomości nie przeskakują luki`() {
        val watermark = UidWatermark(100L)

        watermark.failed(101L)
        watermark.done(102L)
        watermark.done(103L)

        assertEquals(100L, watermark.value())
    }

    @Test
    fun `liczy się najniższa nieudana wiadomość przebiegu`() {
        val watermark = UidWatermark(10L)

        watermark.done(11L)
        watermark.failed(14L)
        watermark.failed(12L)
        watermark.done(15L)

        assertEquals(11L, watermark.value())
    }

    @Test
    fun `znacznik nigdy nie cofa się poniżej stanu zapisanego`() {
        val watermark = UidWatermark(100L)

        watermark.failed(101L)

        assertEquals(100L, watermark.value())
    }
}
