package pl.detailing.crm.instagram.analytics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Percentyl jest kluczem do wskazania „kiedy publikować": to on odcina posty, które
 * zebrały skrajne reakcje z powodów niemających nic wspólnego z porą publikacji —
 * oznaczenia znanego konta, płatnej promocji, przypadkowego virala.
 *
 * Testy pilnują dwóch rzeczy: że wzór jest tym, co za niego uważamy (interpolacja
 * liniowa, typ 7 — ten sam co w NumPy i R), i że pojedynczy wyskok faktycznie wypada
 * z próbki zamiast ją przesuwać.
 */
class MetricsCalculatorPercentileTest {

    @Test
    fun `percentyl 90 z dziesieciu wartosci interpoluje miedzy dwiema najwyzszymi`() {
        // pozycja = 0,9 × 9 = 8,1 → 9 × 0,9 + 100 × 0,1
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 100.0)

        assertEquals(18.1, MetricsCalculator.percentile(values, 0.90)!!, 0.0001)
    }

    @Test
    fun `mediana i skrajne percentyle zgadzaja sie z definicja`() {
        val values = listOf(10.0, 20.0, 30.0, 40.0, 50.0)

        assertEquals(30.0, MetricsCalculator.percentile(values, 0.50)!!, 0.0001)
        assertEquals(10.0, MetricsCalculator.percentile(values, 0.0)!!, 0.0001)
        assertEquals(50.0, MetricsCalculator.percentile(values, 1.0)!!, 0.0001)
    }

    @Test
    fun `kolejnosc wejscia nie ma znaczenia`() {
        val ascending = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val shuffled = listOf(4.0, 1.0, 5.0, 3.0, 2.0)

        assertEquals(
            MetricsCalculator.percentile(ascending, 0.90)!!,
            MetricsCalculator.percentile(shuffled, 0.90)!!,
            0.0001
        )
    }

    @Test
    fun `pusta lista nie ma percentyla`() {
        assertNull(MetricsCalculator.percentile(emptyList(), 0.90))
    }

    @Test
    fun `jedna wartosc jest swoim wlasnym percentylem`() {
        assertEquals(42.0, MetricsCalculator.percentile(listOf(42.0), 0.90)!!, 0.0001)
    }

    /**
     * Sedno sprawy: post-wyskok ma wypaść z próbki, a nie zawyżyć wyniku.
     *
     * Dwadzieścia postów po ~100 reakcji i jeden z 20 000 (oznaczony celebryta).
     * Średnia z całości jest o rząd wielkości wyższa od typowego posta; średnia po
     * odcięciu górnego decyla wraca do tego, co studio realnie zbiera.
     */
    @Test
    fun `odciecie gornego decyla usuwa wyskok zamiast go usredniac`() {
        val typical = List(20) { 100.0 }
        val withOutlier = typical + 20_000.0

        val threshold = MetricsCalculator.percentile(withOutlier, 0.90)!!
        val kept = withOutlier.filter { it <= threshold }

        assertEquals(20, kept.size)
        assertEquals(100.0, kept.average(), 0.0001)
    }
}
