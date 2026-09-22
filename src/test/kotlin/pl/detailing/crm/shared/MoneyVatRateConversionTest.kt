package pl.detailing.crm.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Prymitywy konwersji cen (CLAUDE.md §1): netto → brutto, brutto → netto, VAT jako różnica
 * i rozstrzyganie pary netto/brutto wpisanej przez człowieka.
 */
class MoneyVatRateConversionTest {

    // ── Money.fromAmount ────────────────────────────────────────────────────────

    @Test
    fun `fromAmount zaokragla zlote do groszy - 19,99 to 1999, nie 1998`() {
        // 19.99 * 100 = 1998.9999999999998 w double; obcięcie gubiło grosz
        assertEquals(1_999L, Money.fromAmount(19.99).amountInCents)
        assertEquals(190_000L, Money.fromAmount(1900.00).amountInCents)
        assertEquals(1L, Money.fromAmount(0.01).amountInCents)
    }

    // ── netto → brutto ──────────────────────────────────────────────────────────

    @Test
    fun `brutto z netta - VAT zaokraglony do grosza`() {
        assertEquals(190_001L, VatRate.VAT_23.calculateGrossAmount(Money(154_472)).amountInCents)
        assertEquals(12_300L, VatRate.VAT_23.calculateGrossAmount(Money(10_000)).amountInCents)
        assertEquals(10_800L, VatRate.VAT_8.calculateGrossAmount(Money(10_000)).amountInCents)
        assertEquals(10_500L, VatRate.VAT_5.calculateGrossAmount(Money(10_000)).amountInCents)
    }

    @Test
    fun `stawka 0 i zwolnienie - brutto rowne netto`() {
        assertEquals(10_000L, VatRate.VAT_0.calculateGrossAmount(Money(10_000)).amountInCents)
        assertEquals(10_000L, VatRate.VAT_ZW.calculateGrossAmount(Money(10_000)).amountInCents)
    }

    // ── brutto → netto ──────────────────────────────────────────────────────────

    @Test
    fun `netto z brutto - 1900,00 daje 154472, VAT jako roznica 35528`() {
        val net = VatRate.VAT_23.netCentsFromGrossCents(190_000)

        assertEquals(154_472L, net)
        assertEquals(35_528L, 190_000 - net)
    }

    @Test
    fun `netto z brutto przy zwolnieniu i zerowej stawce - rowne brutto`() {
        assertEquals(25_000L, VatRate.VAT_ZW.netCentsFromGrossCents(25_000))
        assertEquals(25_000L, VatRate.VAT_0.netCentsFromGrossCents(25_000))
    }

    @Test
    fun `brutto do netta i z powrotem nie jest tozsamoscia - dlatego brutto trzymamy osobno`() {
        val net = VatRate.VAT_23.netCentsFromGrossCents(190_000)

        assertEquals(190_001L, VatRate.VAT_23.calculateGrossAmount(Money(net)).amountInCents)
    }

    // ── para netto/brutto ───────────────────────────────────────────────────────

    @Test
    fun `resolveGrossAmount zachowuje wpisane brutto`() {
        assertEquals(Money(190_000), VatRate.VAT_23.resolveGrossAmount(Money(154_472), Money(190_000)))
    }

    @Test
    fun `resolveGrossAmount bez brutto liczy je z netta`() {
        assertEquals(Money(190_001), VatRate.VAT_23.resolveGrossAmount(Money(154_472), null))
    }

    @Test
    fun `resolveGrossAmount odrzuca brutto odbiegajace o wiecej niz grosz`() {
        assertThrows<IllegalArgumentException> {
            VatRate.VAT_23.resolveGrossAmount(Money(154_472), Money(190_003))
        }
    }
}
