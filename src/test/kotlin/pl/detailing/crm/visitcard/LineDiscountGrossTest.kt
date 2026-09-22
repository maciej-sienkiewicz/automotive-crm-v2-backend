package pl.detailing.crm.visitcard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.shared.VatRate

/**
 * Rabat pozycji na karcie wizyty dla klienta. Brutto „przed rabatem" liczone z netta dawało
 * 1900,01 przy cenie 1900,00, więc klient widział „rabat 0,01 zł" przy usłudze bez rabatu.
 */
class LineDiscountGrossTest {

    @Test
    fun `rabat zerowy to zero, takze przy cenie wpisanej od brutto`() {
        assertEquals(0L, lineDiscountGross(154_472, null, VatRate.VAT_23, AdjustmentType.PERCENT, 0, 190_000))
        assertEquals(0L, lineDiscountGross(154_472, 190_000, VatRate.VAT_23, AdjustmentType.FIXED_GROSS, 0, 190_000))
    }

    @Test
    fun `upust brutto 100 zl od 1900,00 zl to rabat 100,00 zl, nie 100,01`() {
        assertEquals(
            10_000L,
            lineDiscountGross(154_472, 190_000, VatRate.VAT_23, AdjustmentType.FIXED_GROSS, 10_000, 180_000)
        )
    }

    @Test
    fun `ustawiona cena brutto - rabat od dokladnego brutto bazy`() {
        assertEquals(
            40_000L,
            lineDiscountGross(154_472, 190_000, VatRate.VAT_23, AdjustmentType.SET_GROSS, 150_000, 150_000)
        )
    }

    @Test
    fun `bez dokladnego brutto baza liczy sie z netta`() {
        // 100000 netto → 123000 brutto; −10% → 90000 netto → 110700 brutto
        assertEquals(
            12_300L,
            lineDiscountGross(100_000, null, VatRate.VAT_23, AdjustmentType.PERCENT, -1_000, 110_700)
        )
    }

    @Test
    fun `narzut nie jest ujemnym rabatem`() {
        assertEquals(
            0L,
            lineDiscountGross(100_000, 123_000, VatRate.VAT_23, AdjustmentType.PERCENT, 1_000, 135_300)
        )
    }

    @Test
    fun `zwolnienie z VAT - brutto rowne netto`() {
        assertEquals(
            10_000L,
            lineDiscountGross(100_000, null, VatRate.VAT_ZW, AdjustmentType.FIXED_NET, 10_000, 90_000)
        )
    }
}
