package pl.detailing.crm.appointment.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.VatRate

/**
 * Cena pozycji z cennika przy stawce VAT pozycji. Brutto cennika obowiązuje tylko przy
 * stawce cennika — przekazane przy innej wywracało kontrolę spójności pozycji i cały zapis
 * rezerwacji (kalendarz wysyłał domyślne 23% także dla usług na 8%).
 */
class CatalogLinePriceTest {

    /** Usługa wpisana w cenniku jako 1900,00 zł brutto — z netta wyszłoby 1900,01 zł. */
    private val typedNet = Money(154_472)
    private val typedGross = Money(190_000)

    /** Usługa z parą zgodną z przeliczeniem — nie wiadomo, którą stronę wpisano. */
    private val plainNet = Money(100_000)
    private val plainGross = Money(123_000)

    private fun price(net: Money, gross: Money, lineVat: VatRate, catalogVat: VatRate = VatRate.VAT_23) =
        catalogLinePrice(false, net, gross, catalogVat, lineVat, requestedNetCents = 999_999, requestedGrossCents = 999_999)

    @Test
    fun `ta sama stawka - para z cennika bez zmian`() {
        assertEquals(typedNet to typedGross, price(typedNet, typedGross, VatRate.VAT_23))
    }

    @Test
    fun `inna stawka i brutto wpisane w cenniku - brutto zostaje, netto z niego`() {
        val (net, gross) = price(typedNet, typedGross, VatRate.VAT_8)

        assertEquals(Money(175_926), net) // round(190000 · 100 / 108)
        assertEquals(typedGross, gross)
    }

    @Test
    fun `inna stawka i para niejednoznaczna - zostaje netto, brutto liczy sie z niego`() {
        assertEquals(plainNet to null, price(plainNet, plainGross, VatRate.VAT_8))
    }

    @Test
    fun `zwolnienie z VAT przy bruttcie wpisanym - netto rowne bruttu`() {
        assertEquals(typedGross to typedGross, price(typedNet, typedGross, VatRate.VAT_ZW))
    }

    @Test
    fun `wynik przechodzi kontrole spojnosci pozycji rezerwacji`() {
        for (lineVat in VatRate.entries) {
            for ((net, gross) in listOf(typedNet to typedGross, plainNet to plainGross)) {
                val (baseNet, baseGross) = price(net, gross, lineVat)
                // Rzuciłoby „Financial integrity violation", gdyby para nie pasowała do stawki.
                val line = AppointmentLineItem.create(
                    serviceId = null, serviceName = "Usługa", basePriceNet = baseNet, vatRate = lineVat,
                    adjustmentType = AdjustmentType.PERCENT, adjustmentValue = 0, customNote = null,
                    basePriceGross = baseGross
                )
                assertEquals(baseGross ?: lineVat.calculateGrossAmount(baseNet), line.finalPriceGross)
            }
        }
    }

    @Test
    fun `cena reczna - dalej z zadania, niezaleznie od stawki`() {
        val (net, gross) = catalogLinePrice(
            true, Money.ZERO, Money.ZERO, VatRate.VAT_23, VatRate.VAT_8,
            requestedNetCents = 154_472, requestedGrossCents = 190_000
        )

        assertEquals(Money(154_472), net)
        assertEquals(Money(190_000), gross)
    }
}
