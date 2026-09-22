package pl.detailing.crm.appointment.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.VatRate
import pl.detailing.crm.visit.domain.PriceCalculator

/**
 * Specyfikacja cen pozycji rezerwacji — ta sama co dla pozycji wizyty ([PriceCalculator]).
 *
 * Rezerwacja i wizyta to jeden cennik: check-in przepisuje pozycję rezerwacji na
 * pozycję wizyty razem z `adjustmentValue`, a każda późniejsza edycja pozycji wizyty
 * liczy ją [PriceCalculator]-em. Dwa silniki z różnymi wzorami oznaczały, że ta sama
 * zapisana wartość rabatu dawała inną cenę przed i po check-inie.
 *
 * Regresja z produkcji: formularz rezerwacji wysyła rabat kwotowy jako wartość DODATNIĄ
 * (`ServiceItem.tsx`: `Math.abs(value)`), a stary silnik rezerwacji ją DODAWAŁ —
 * „rabat 100 zł" zapisywał się jako narzut 100 zł, a check-in przenosił narzut na wizytę.
 *
 * Wzory (kwoty w groszach, `v` — adjustmentValue):
 *   FIXED_NET:   F_net = B_net − v                      (v > 0 → rabat)
 *   FIXED_GROSS: F_gross = B_gross − v, F_net z brutto  (v > 0 → rabat)
 *   SET_NET:     F_net = v
 *   SET_GROSS:   F_gross = v, F_net = round(v·100/(100+r))
 *   PERCENT:     pct = round(B_net·|v_bp|/10000), F_net = B_net ∓ pct
 * Zasada kierunkowa (CLAUDE.md §1): brutto wpisane przez człowieka jest źródłem prawdy
 * i nigdy nie jest odtwarzane z netta; VAT = brutto − netto.
 */
class AppointmentLineItemPricingSpecTest {

    private fun create(
        basePriceNet: Long,
        adjustmentType: AdjustmentType,
        adjustmentValue: Long,
        basePriceGross: Long? = null,
        vatRate: VatRate = VatRate.VAT_23
    ) = AppointmentLineItem.create(
        serviceId = null,
        serviceName = "Usługa testowa",
        basePriceNet = Money.fromCents(basePriceNet),
        vatRate = vatRate,
        adjustmentType = adjustmentType,
        adjustmentValue = adjustmentValue,
        customNote = null,
        basePriceGross = basePriceGross?.let { Money.fromCents(it) }
    )

    // ── Znak rabatu kwotowego ───────────────────────────────────────────────────

    @Test
    fun `FIXED_NET z dodatnia wartoscia to rabat, nie narzut`() {
        // 100,00 zł netto − 16,67 zł rabatu = 83,33 zł; brutto 83,33 + round(19,1659) = 102,50
        val item = create(basePriceNet = 10_000, adjustmentType = AdjustmentType.FIXED_NET, adjustmentValue = 1_667)

        assertEquals(8_333L, item.finalPriceNet.amountInCents)
        assertEquals(10_250L, item.finalPriceGross.amountInCents)
    }

    @Test
    fun `FIXED_GROSS z dodatnia wartoscia to rabat od dokladnego brutto`() {
        // 1900,00 zł brutto − 100,00 zł = 1800,00 zł dokładnie; netto „w stu": round(180000·100/123) = 146341
        val item = create(
            basePriceNet = 154_472, basePriceGross = 190_000,
            adjustmentType = AdjustmentType.FIXED_GROSS, adjustmentValue = 10_000
        )

        assertEquals(180_000L, item.finalPriceGross.amountInCents)
        assertEquals(146_341L, item.finalPriceNet.amountInCents)
    }

    @Test
    fun `FIXED_GROSS bez znanego brutto liczy od brutto wyprowadzonego z netta`() {
        // cena wpisana od strony netto: brutto bazowe = 154472 + round(35528,56) = 190001; − 100 zł = 180001
        val item = create(basePriceNet = 154_472, adjustmentType = AdjustmentType.FIXED_GROSS, adjustmentValue = 10_000)

        assertEquals(180_001L, item.finalPriceGross.amountInCents)
        assertEquals(146_342L, item.finalPriceNet.amountInCents)
    }

    // ── Netto liczone z brutto: zaokrąglenie, nie obcięcie ──────────────────────

    @Test
    fun `SET_GROSS 1900,00 daje netto 154472 i VAT 35528 - zaokraglenie, nie obciecie`() {
        // 190000·100/123 = 154471,54 → 154472 (obcięcie dawało 154471 i VAT 35529)
        val item = create(basePriceNet = 0, adjustmentType = AdjustmentType.SET_GROSS, adjustmentValue = 190_000)

        assertEquals(190_000L, item.finalPriceGross.amountInCents)
        assertEquals(154_472L, item.finalPriceNet.amountInCents)
        assertEquals(35_528L, item.finalPriceGross.amountInCents - item.finalPriceNet.amountInCents)
    }

    @Test
    fun `SET_GROSS przy 8 procent VAT daje rowne netto`() {
        val item = create(basePriceNet = 0, adjustmentType = AdjustmentType.SET_GROSS, adjustmentValue = 108_000, vatRate = VatRate.VAT_8)

        assertEquals(100_000L, item.finalPriceNet.amountInCents)
        assertEquals(108_000L, item.finalPriceGross.amountInCents)
    }

    @Test
    fun `SET_GROSS przy zwolnieniu z VAT - netto rowne brutto`() {
        val item = create(basePriceNet = 0, adjustmentType = AdjustmentType.SET_GROSS, adjustmentValue = 25_000, vatRate = VatRate.VAT_ZW)

        assertEquals(25_000L, item.finalPriceNet.amountInCents)
        assertEquals(25_000L, item.finalPriceGross.amountInCents)
    }

    @Test
    fun `PERCENT zaokragla kwote rabatu, nie obcina netta`() {
        // 10% z 10,03 zł = 1,003 → round = 1,00 → 9,03 zł (obcięcie 1003·0,9 = 902,7 dawało 9,02 zł)
        val item = create(basePriceNet = 1_003, adjustmentType = AdjustmentType.PERCENT, adjustmentValue = -1_000)

        assertEquals(903L, item.finalPriceNet.amountInCents)
    }

    // ── Jeden cennik: rezerwacja liczy dokładnie tak jak wizyta ─────────────────

    @Test
    fun `rezerwacja liczy kazda kombinacje dokladnie tak jak PriceCalculator wizyty`() {
        val bases = listOf(1L, 99L, 1_003L, 10_000L, 154_472L, 999_999L)
        val rates = VatRate.entries
        val adjustments = listOf(
            AdjustmentType.PERCENT to listOf(0L, -1_000L, -3_333L, 500L),
            AdjustmentType.FIXED_NET to listOf(0L, 1L, 50L),
            AdjustmentType.FIXED_GROSS to listOf(0L, 1L, 50L),
            AdjustmentType.SET_NET to listOf(0L, 5_000L),
            AdjustmentType.SET_GROSS to listOf(0L, 190_000L)
        )

        for (base in bases) for (rate in rates) for ((type, values) in adjustments) for (value in values) {
            // Brutto bazowe: nieznane (cena od netto), równe wyprowadzonemu z netta, albo wpisane
            // od brutto i różne od wyprowadzonego o grosz — jak 1900,00 przy netto 154472.
            val derivedGross = rate.calculateGrossAmount(Money(base))
            val grossEntered = if (base >= 1_000) Money(derivedGross.amountInCents - 1) else null
            for (baseGross in listOf(null, derivedGross, grossEntered).distinct()) {
                // Rabat większy niż cena: wizyta odrzuca go walidacją, rezerwacja przycina do zera —
                // to świadomie różne zachowanie na granicy, więc porównujemy tylko ceny poprawne.
                val expectedNet = runCatching {
                    PriceCalculator.calculateFinalNet(Money(base), rate, type, value, baseGross)
                }.getOrNull() ?: continue
                val expectedGross = PriceCalculator.calculateFinalGross(expectedNet, Money(base), rate, type, value, baseGross)

                val item = create(base, type, value, baseGross?.amountInCents, rate)

                val case = "base=$base rate=$rate $type v=$value baseGross=$baseGross"
                assertEquals(expectedNet, item.finalPriceNet, "netto: $case")
                assertEquals(expectedGross, item.finalPriceGross, "brutto: $case")
            }
        }
    }
}
