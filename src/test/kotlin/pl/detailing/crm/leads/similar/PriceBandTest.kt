package pl.detailing.crm.leads.similar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.leads.similar.pricing.AbstentionPolicy
import pl.detailing.crm.leads.similar.pricing.AnchorVerdict
import pl.detailing.crm.leads.similar.pricing.GateThresholds
import pl.detailing.crm.leads.similar.pricing.PriceBand

/**
 * Pasmo cenowe i polityka abstencji — produktem sekcji jest PRZEDZIAŁ z medianą
 * i uczciwe „nie wiem", nie lista wizyt.
 */
class PriceBandTest {

    private val thresholds = GateThresholds()

    @Test
    fun `mediana liczy sie po sortowaniu, nie po kolejnosci wejscia`() {
        val band = PriceBand.of(listOf(220_000, 180_000, 200_000), anchorGross = null)!!
        assertEquals(200_000L, band.median)
        assertEquals(180_000L, band.min)
        assertEquals(220_000L, band.max)
        assertEquals(3, band.sampleSize)
    }

    @Test
    fun `mediana z parzystej liczby to srodek dwoch srodkowych`() {
        val band = PriceBand.of(listOf(180_000, 220_000), anchorGross = null)!!
        assertEquals(200_000L, band.median)
    }

    @Test
    fun `kwoty zerowe nie wchodza do pasma`() {
        assertNull(PriceBand.of(listOf(0, 0), anchorGross = null))
        assertEquals(1, PriceBand.of(listOf(0, 50_000), anchorGross = null)!!.sampleSize)
    }

    /**
     * Zestaw z przypadku PPF był podręcznikowo jednostronny: same realizacje
     * wielokrotnie mniejsze od kotwicy. Pokazany jako „przedział" kłamałby —
     * flaga każe interfejsowi powiedzieć „wszystkie porównania to mniejsze roboty".
     */
    @Test
    fun `same mniejsze realizacje daja flage jednostronnosci`() {
        val band = PriceBand.of(listOf(85_000, 40_000, 90_000), anchorGross = 1_845_000)!!
        assertTrue(band.oneSided)

        val balanced = PriceBand.of(listOf(1_700_000, 1_900_000), anchorGross = 1_845_000)!!
        assertFalse(balanced.oneSided)
    }

    @Test
    fun `rozbieznosc katalog historia wychodzi jako ulamek kotwicy`() {
        val band = PriceBand.of(listOf(90_000, 100_000, 110_000), anchorGross = 100_000)!!
        assertEquals(0.0, band.divergence!!, 0.001)

        val cheaper = PriceBand.of(listOf(80_000, 90_000, 100_000), anchorGross = 100_000)!!
        assertEquals(-0.1, cheaper.divergence!!, 0.001)
    }

    // ── Polityka abstencji ───────────────────────────────────────────────────

    @Test
    fun `zero compow to nazwane milczenie`() {
        val outcome = AbstentionPolicy.decide(0, null, thresholds)
        assertEquals(AnchorVerdict.ABSTAIN, outcome.verdict)
        assertEquals(AbstentionPolicy.CODE_NO_COMPARABLE, outcome.abstentionCode)
    }

    @Test
    fun `jedna realizacja to SINGLE, nie udawany przedzial`() {
        val band = PriceBand.of(listOf(70_000), anchorGross = null)
        assertEquals(AnchorVerdict.SINGLE, AbstentionPolicy.decide(1, band, thresholds).verdict)
    }

    @Test
    fun `dwie realizacje to dowod rzemiosla, nie cena`() {
        val band = PriceBand.of(listOf(70_000, 80_000), anchorGross = null)
        assertEquals(AnchorVerdict.EVIDENCE_ONLY, AbstentionPolicy.decide(2, band, thresholds).verdict)
    }

    @Test
    fun `trzy zgodne realizacje daja pasmo`() {
        val band = PriceBand.of(listOf(70_000, 80_000, 90_000), anchorGross = null)
        assertEquals(AnchorVerdict.BAND, AbstentionPolicy.decide(3, band, thresholds).verdict)
    }

    /** „Od 1 200 do 6 400 zł" to nie jest cena, tylko rozrzut. */
    @Test
    fun `pasmo szersze niz prog spada do dowodu rzemiosla`() {
        val wide = PriceBand.of(listOf(120_000, 300_000, 640_000), anchorGross = null)
        assertEquals(AnchorVerdict.EVIDENCE_ONLY, AbstentionPolicy.decide(3, wide, thresholds).verdict)
    }
}
