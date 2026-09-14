package pl.detailing.crm.visit.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.shared.*

/**
 * Tests for PriceCalculator and VatRate rounding.
 *
 * All monetary values in grosz (integer cents): 10000 = 100.00 PLN.
 * Formulas per spec:
 *   FIXED_NET:   F_net = B_net - v;  F_vat = round(F_net * r/100); F_gross = F_net + F_vat
 *   FIXED_GROSS: F_gross = B_gross - v; F_net = round(F_gross * 100/(100+r))
 *   SET_NET:     F_net = v
 *   SET_GROSS:   F_net = round(v * 100/(100+r))
 *   PERCENT:     pct = round(B_net * |v_bp| / 10000); F_net = B_net ± pct  (bp = basis points)
 */
class PriceCalculatorTest {

    // ── FIXED_NET ──────────────────────────────────────────────────────────────

    @Test
    fun `FIXED_NET subtracts discount from base net`() {
        // B_net=10000, v=1667, r=23
        // F_net = 10000 - 1667 = 8333
        // F_vat = round(8333 * 23 / 100) = round(1916.59) = 1917
        // F_gross = 8333 + 1917 = 10250
        val fNet = PriceCalculator.calculateFinalNet(Money(10000), VatRate.VAT_23, AdjustmentType.FIXED_NET, 1667)
        val fGross = VatRate.VAT_23.calculateGrossAmount(fNet)

        assertEquals(8333, fNet.amountInCents)
        assertEquals(10250, fGross.amountInCents)
    }

    @Test
    fun `FIXED_NET with zero discount returns base price unchanged`() {
        val fNet = PriceCalculator.calculateFinalNet(Money(10000), VatRate.VAT_23, AdjustmentType.FIXED_NET, 0)
        assertEquals(10000, fNet.amountInCents)
    }

    @Test
    fun `FIXED_NET with discount equal to full price yields zero`() {
        val fNet = PriceCalculator.calculateFinalNet(Money(10000), VatRate.VAT_23, AdjustmentType.FIXED_NET, 10000)
        assertEquals(0, fNet.amountInCents)
    }

    @Test
    fun `FIXED_NET with discount exceeding price throws ValidationException`() {
        assertThrows<ValidationException> {
            PriceCalculator.calculateFinalNet(Money(10000), VatRate.VAT_23, AdjustmentType.FIXED_NET, 10001)
        }
    }

    // ── FIXED_GROSS ────────────────────────────────────────────────────────────

    @Test
    fun `FIXED_GROSS subtracts discount from base gross and recalculates net`() {
        // B_net=20000, r=23 → B_gross = 20000 + round(20000*23/100) = 20000+4600 = 24600
        // v=3333 → F_gross = 24600 - 3333 = 21267
        // F_net = round(21267 * 100 / 123) = round(17290.24) = 17290
        val fNet = PriceCalculator.calculateFinalNet(Money(20000), VatRate.VAT_23, AdjustmentType.FIXED_GROSS, 3333)
        val fGross = VatRate.VAT_23.calculateGrossAmount(fNet)

        assertEquals(17290, fNet.amountInCents)
        // F_vat = round(17290 * 23/100) = round(3976.7) = 3977
        // F_gross = 17290 + 3977 = 21267
        assertEquals(21267, fGross.amountInCents)
    }

    @Test
    fun `FIXED_GROSS with zero discount returns base price unchanged`() {
        val fNet = PriceCalculator.calculateFinalNet(Money(10000), VatRate.VAT_23, AdjustmentType.FIXED_GROSS, 0)
        assertEquals(10000, fNet.amountInCents)
    }

    @Test
    fun `FIXED_GROSS discount exceeding gross throws ValidationException`() {
        // B_gross = 10000 + round(10000*23/100) = 12300; discount 12301 → F_gross < 0
        assertThrows<ValidationException> {
            PriceCalculator.calculateFinalNet(Money(10000), VatRate.VAT_23, AdjustmentType.FIXED_GROSS, 12301)
        }
    }

    // ── SET_NET ────────────────────────────────────────────────────────────────

    @Test
    fun `SET_NET overrides net price directly`() {
        val fNet = PriceCalculator.calculateFinalNet(Money(10000), VatRate.VAT_23, AdjustmentType.SET_NET, 7500)
        assertEquals(7500, fNet.amountInCents)
    }

    @Test
    fun `SET_NET with zero yields zero`() {
        val fNet = PriceCalculator.calculateFinalNet(Money(10000), VatRate.VAT_23, AdjustmentType.SET_NET, 0)
        assertEquals(0, fNet.amountInCents)
    }

    @Test
    fun `SET_NET with negative value throws ValidationException`() {
        assertThrows<ValidationException> {
            PriceCalculator.calculateFinalNet(Money(10000), VatRate.VAT_23, AdjustmentType.SET_NET, -1)
        }
    }

    // ── SET_GROSS ──────────────────────────────────────────────────────────────

    @Test
    fun `SET_GROSS derives net from target gross`() {
        // F_gross = 12300 → F_net = round(12300 * 100 / 123) = round(10000) = 10000
        val fNet = PriceCalculator.calculateFinalNet(Money(5000), VatRate.VAT_23, AdjustmentType.SET_GROSS, 12300)
        assertEquals(10000, fNet.amountInCents)
    }

    @Test
    fun `SET_GROSS with zero yields zero net`() {
        val fNet = PriceCalculator.calculateFinalNet(Money(10000), VatRate.VAT_23, AdjustmentType.SET_GROSS, 0)
        assertEquals(0, fNet.amountInCents)
    }

    // ── PERCENT ────────────────────────────────────────────────────────────────

    @Test
    fun `PERCENT negative value applies discount`() {
        // v = -10% → stored as -1000 bp
        // pct = round(10000 * 1000 / 10000) = 1000; F_net = 10000 - 1000 = 9000
        val fNet = PriceCalculator.calculateFinalNet(Money(10000), VatRate.VAT_23, AdjustmentType.PERCENT, -1000)
        assertEquals(9000, fNet.amountInCents)
    }

    @Test
    fun `PERCENT positive value applies markup`() {
        // v = +10% → stored as +1000 bp
        // pct = round(10000 * 1000 / 10000) = 1000; F_net = 10000 + 1000 = 11000
        val fNet = PriceCalculator.calculateFinalNet(Money(10000), VatRate.VAT_23, AdjustmentType.PERCENT, 1000)
        assertEquals(11000, fNet.amountInCents)
    }

    @Test
    fun `PERCENT with fractional percentage rounds correctly`() {
        // v = -10.5% → stored as -1050 bp
        // pct = round(10000 * 1050 / 10000) = round(1050) = 1050; F_net = 10000 - 1050 = 8950
        val fNet = PriceCalculator.calculateFinalNet(Money(10000), VatRate.VAT_23, AdjustmentType.PERCENT, -1050)
        assertEquals(8950, fNet.amountInCents)
    }

    @Test
    fun `PERCENT zero has no effect`() {
        val fNet = PriceCalculator.calculateFinalNet(Money(10000), VatRate.VAT_23, AdjustmentType.PERCENT, 0)
        assertEquals(10000, fNet.amountInCents)
    }

    @Test
    fun `PERCENT 100 discount throws ValidationException`() {
        // -100% → stored as -10000 bp; F_net = 10000 - 10000 = 0 (edge: exactly zero, valid)
        val fNet = PriceCalculator.calculateFinalNet(Money(10000), VatRate.VAT_23, AdjustmentType.PERCENT, -10000)
        assertEquals(0, fNet.amountInCents)
    }

    @Test
    fun `PERCENT exceeding 100 discount throws ValidationException`() {
        // -110% → stored as -11000 bp; F_net = 10000 - 11000 = -1000 → ValidationException
        assertThrows<ValidationException> {
            PriceCalculator.calculateFinalNet(Money(10000), VatRate.VAT_23, AdjustmentType.PERCENT, -11000)
        }
    }

    // ── VAT rounding ──────────────────────────────────────────────────────────

    @Test
    fun `calculateVatAmount rounds half-up`() {
        // 8333 * 23 / 100 = 1916.59 → should round to 1917
        val vat = VatRate.VAT_23.calculateVatAmount(Money(8333))
        assertEquals(1917, vat.amountInCents)
    }

    @Test
    fun `calculateGrossAmount uses rounded VAT`() {
        // Net=8333, VAT=1917 → gross=10250 (from spec example)
        val gross = VatRate.VAT_23.calculateGrossAmount(Money(8333))
        assertEquals(10250, gross.amountInCents)
    }

    // ── Spec example: two services ────────────────────────────────────────────

    @Test
    fun `spec example two FIXED_NET services total`() {
        // Service 1: B_net=10000, FIXED_NET v=1667 → F_net=8333, F_gross=10250
        // Service 2: B_net=20000, FIXED_NET v=3333 → F_net=16667, F_gross=20500
        // Total: net=25000, gross=30750
        val net1 = PriceCalculator.calculateFinalNet(Money(10000), VatRate.VAT_23, AdjustmentType.FIXED_NET, 1667)
        val gross1 = VatRate.VAT_23.calculateGrossAmount(net1)
        val net2 = PriceCalculator.calculateFinalNet(Money(20000), VatRate.VAT_23, AdjustmentType.FIXED_NET, 3333)
        val gross2 = VatRate.VAT_23.calculateGrossAmount(net2)

        assertEquals(8333, net1.amountInCents)
        assertEquals(10250, gross1.amountInCents)
        assertEquals(16667, net2.amountInCents)
        assertEquals(20500, gross2.amountInCents)
        assertEquals(25000, net1.amountInCents + net2.amountInCents)
        assertEquals(30750, gross1.amountInCents + gross2.amountInCents)
    }

    // ── VAT zwolniony (ZW) — regresja: netto nie może przewyższać brutta ─────────
    //
    // VAT_ZW.rate to wartownik -1, nie realna stawka. Wzór „w stu"
    // brutto*100/(100+rate) dawał dla ZW dzielenie przez 99, więc netto WYŻSZE niż
    // brutto (250,00 → 252,53). Przy zakończeniu wizyty Visit.calculateTotalVat()
    // = brutto - netto = -253 gr wysadzało niezmiennik Money(>=0) i dokument
    // finansowy nigdy nie powstawał. Dla zwolnienia netto = brutto.

    @Test
    fun `SET_GROSS przy ZW daje netto rowne brutto, nie wyzsze`() {
        // Dokładne dane z produkcji: SET_GROSS 250,00 zł przy stawce ZW.
        val fNet = PriceCalculator.calculateFinalNet(Money(0), VatRate.VAT_ZW, AdjustmentType.SET_GROSS, 25000)
        val fGross = PriceCalculator.calculateFinalGross(
            fNet, Money(0), VatRate.VAT_ZW, AdjustmentType.SET_GROSS, 25000, null
        )
        assertEquals(25000, fNet.amountInCents, "ZW: netto = brutto, nie 25253")
        assertEquals(25000, fGross.amountInCents)
        // VAT wizyty (brutto - netto) już nie jest ujemny i nie wysadza Money.
        assertEquals(0, fGross.amountInCents - fNet.amountInCents)
    }

    @Test
    fun `FIXED_GROSS przy ZW trzyma netto rowne brutto`() {
        // B_net = 30000, ZW → B_gross = 30000; rabat gross 5000 → F_gross = 25000, F_net = 25000.
        val fNet = PriceCalculator.calculateFinalNet(Money(30000), VatRate.VAT_ZW, AdjustmentType.FIXED_GROSS, 5000)
        val fGross = PriceCalculator.calculateFinalGross(
            fNet, Money(30000), VatRate.VAT_ZW, AdjustmentType.FIXED_GROSS, 5000, null
        )
        assertEquals(25000, fNet.amountInCents)
        assertEquals(25000, fGross.amountInCents)
    }

    @Test
    fun `SET_GROSS przy 0 procent tez daje netto rowne brutto`() {
        // Stawka 0% (rate 0) — brak VAT-u, więc netto = brutto (bez dzielenia przez 100).
        val fNet = PriceCalculator.calculateFinalNet(Money(0), VatRate.VAT_0, AdjustmentType.SET_GROSS, 25000)
        assertEquals(25000, fNet.amountInCents)
    }

    @Test
    fun `SET_GROSS przy VAT 23 pozostaje bez zmian po poprawce`() {
        // Regresja w drugą stronę: realna stawka liczy się jak dotąd (250,00 → netto 203,25).
        val fNet = PriceCalculator.calculateFinalNet(Money(0), VatRate.VAT_23, AdjustmentType.SET_GROSS, 25000)
        assertEquals(20325, fNet.amountInCents)
    }

    @Test
    fun `netCentsFromGrossCents jest odwrotnoscia calculateGrossAmount dla ZW i realnych stawek`() {
        assertEquals(25000, VatRate.VAT_ZW.netCentsFromGrossCents(25000))
        assertEquals(25000, VatRate.VAT_0.netCentsFromGrossCents(25000))
        assertEquals(20325, VatRate.VAT_23.netCentsFromGrossCents(25000))
        // Wejście ujemne przechodzi bez zmiany (walidację robi PriceCalculator, nie Money).
        assertEquals(-100, VatRate.VAT_ZW.netCentsFromGrossCents(-100))
    }
}
