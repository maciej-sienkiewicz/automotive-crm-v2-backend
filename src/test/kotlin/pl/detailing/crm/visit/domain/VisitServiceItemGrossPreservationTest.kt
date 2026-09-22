package pl.detailing.crm.visit.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.VatRate

/**
 * Pozycja wizyty pamięta brutto, które wpisał człowiek (CLAUDE.md §1).
 *
 * Regresja z produkcji: usługa za 1900,00 zł brutto trafiała na paragon i fakturę jako
 * 1900,01 zł. Pozycja wizyty trzymała tylko netto; dokładne brutto było użyte raz, przy
 * tworzeniu, a każda późniejsza edycja (powrót do ceny katalogowej, zmiana rabatu,
 * zmiana ilości) odtwarzała je z netta: 154472 gr netto → 190001 gr brutto.
 *
 * Zasada: wpisane brutto → brutto zostaje dokładnie, netto liczone z brutto;
 * wpisane netto → brutto liczone z netta. Użytkownik zawsze widzi to, co wpisał.
 */
class VisitServiceItemGrossPreservationTest {

    private fun pending(
        basePriceNet: Long,
        basePriceGross: Long?,
        adjustmentType: AdjustmentType = AdjustmentType.PERCENT,
        adjustmentValue: Long = 0,
        vatRate: VatRate = VatRate.VAT_23
    ) = VisitServiceItem.createPending(
        serviceId = null,
        serviceName = "Powłoka ceramiczna",
        basePriceNet = Money(basePriceNet),
        vatRate = vatRate,
        adjustmentType = adjustmentType,
        adjustmentValue = adjustmentValue,
        customNote = null,
        basePriceGross = basePriceGross?.let { Money(it) }
    )

    private fun confirmed(
        basePriceNet: Long,
        basePriceGross: Long?,
        adjustmentType: AdjustmentType = AdjustmentType.PERCENT,
        adjustmentValue: Long = 0,
        vatRate: VatRate = VatRate.VAT_23
    ) = pending(basePriceNet, basePriceGross, adjustmentType, adjustmentValue, vatRate).approve()!!

    // ── Tworzenie ───────────────────────────────────────────────────────────────

    @Test
    fun `nowa pozycja zapamietuje dokladne brutto bazowe`() {
        val item = pending(basePriceNet = 154_472, basePriceGross = 190_000)

        assertEquals(Money(190_000), item.basePriceGross)
        assertEquals(190_000L, item.finalPriceGross.amountInCents)
    }

    @Test
    fun `pozycja z cena od netto nie ma brutto bazowego i liczy brutto z netta`() {
        val item = pending(basePriceNet = 154_472, basePriceGross = null)

        assertNull(item.basePriceGross)
        assertEquals(190_001L, item.finalPriceGross.amountInCents)
    }

    // ── Edycja pozycji potwierdzonej ────────────────────────────────────────────

    @Test
    fun `cofniecie rabatu do zera wraca do 1900,00 - nie do 1900,01`() {
        // najpierw 10% rabatu, potem „wróć do ceny katalogowej" (rabat 0) — cena bazowa bez zmian
        val discounted = confirmed(154_472, 190_000, AdjustmentType.PERCENT, -1_000)

        val restored = discounted.toPending(
            newBasePriceNet = Money(154_472),
            newAdjustmentType = AdjustmentType.PERCENT,
            newAdjustmentValue = 0
        )

        assertEquals(190_000L, restored.finalPriceGross.amountInCents)
        assertEquals(154_472L, restored.finalPriceNet.amountInCents)
    }

    @Test
    fun `rabat procentowy liczy brutto z rabatowanego netta`() {
        // rabat od netta zmienia kwotę bazową, więc brutto NALEŻY policzyć (CLAUDE.md §1)
        val item = confirmed(154_472, 190_000)

        val discounted = item.toPending(
            newBasePriceNet = Money(154_472),
            newAdjustmentType = AdjustmentType.PERCENT,
            newAdjustmentValue = -1_000
        )

        // 154472 − round(15447,2) = 139025; brutto 139025 + round(31975,75) = 171001
        assertEquals(139_025L, discounted.finalPriceNet.amountInCents)
        assertEquals(171_001L, discounted.finalPriceGross.amountInCents)
        assertEquals(Money(190_000), discounted.basePriceGross)
    }

    @Test
    fun `nowe brutto wpisane na wizycie zostaje dokladnie`() {
        val item = confirmed(100_000, null)

        val edited = item.toPending(newBasePriceNet = Money(154_472), newBasePriceGross = Money(190_000))

        assertEquals(Money(190_000), edited.basePriceGross)
        assertEquals(190_000L, edited.finalPriceGross.amountInCents)
        assertEquals(154_472L, edited.finalPriceNet.amountInCents)
    }

    @Test
    fun `nowa cena wpisana od netto nie dziedziczy starego brutto`() {
        val item = confirmed(154_472, 190_000)

        val edited = item.toPending(newBasePriceNet = Money(100_000))

        assertNull(edited.basePriceGross)
        assertEquals(123_000L, edited.finalPriceGross.amountInCents)
    }

    @Test
    fun `zmiana stawki VAT przy cenie od brutto trzyma wpisane brutto`() {
        val item = confirmed(154_472, 190_000)
        // 190000·100/108 = 175925,93 → 175926; 175926 + round(14074,08) = 190000
        val net8 = VatRate.VAT_8.netCentsFromGrossCents(190_000)

        val edited = item.toPending(
            newBasePriceNet = Money(net8),
            newVatRate = VatRate.VAT_8,
            newBasePriceGross = Money(190_000)
        )

        assertEquals(175_926L, edited.finalPriceNet.amountInCents)
        assertEquals(190_000L, edited.finalPriceGross.amountInCents)
    }

    @Test
    fun `odrzucenie edycji przywraca dokladne brutto ze snapshotu`() {
        val item = confirmed(154_472, 190_000)
        val edited = item.toPending(newBasePriceNet = Money(100_000))

        val rolledBack = edited.reject()!!

        assertEquals(Money(190_000), rolledBack.basePriceGross)
        assertEquals(190_000L, rolledBack.finalPriceGross.amountInCents)
        assertEquals(154_472L, rolledBack.finalPriceNet.amountInCents)
    }

    // ── Rabat kwotowy od brutto ─────────────────────────────────────────────────

    @Test
    fun `FIXED_GROSS odejmuje od dokladnego brutto - 1900,00 minus 100,00 to 1800,00`() {
        val item = pending(154_472, 190_000, AdjustmentType.FIXED_GROSS, 10_000)

        assertEquals(180_000L, item.finalPriceGross.amountInCents)
        // netto „w stu" z dokładnego brutto: round(180000·100/123) = 146341
        assertEquals(146_341L, item.finalPriceNet.amountInCents)
    }

    @Test
    fun `PriceCalculator liczy netto FIXED_GROSS z dokladnego brutto, gdy je zna`() {
        val withGross = PriceCalculator.calculateFinalNet(
            Money(154_472), VatRate.VAT_23, AdjustmentType.FIXED_GROSS, 10_000, Money(190_000)
        )
        val netSide = PriceCalculator.calculateFinalNet(
            Money(154_472), VatRate.VAT_23, AdjustmentType.FIXED_GROSS, 10_000
        )

        assertEquals(146_341L, withGross.amountInCents)
        // cena od netto: brutto bazowe 190001 − 10000 = 180001 → round(146342,28) = 146342
        assertEquals(146_342L, netSide.amountInCents)
    }

    // ── Sumy wizyty ─────────────────────────────────────────────────────────────

    @Test
    fun `VAT pozycji to roznica brutto minus netto`() {
        val item = pending(154_472, 190_000)

        assertEquals(35_528L, item.finalPriceGross.amountInCents - item.finalPriceNet.amountInCents)
    }
}
