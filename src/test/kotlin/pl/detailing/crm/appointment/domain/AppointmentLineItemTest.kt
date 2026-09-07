package pl.detailing.crm.appointment.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.VatRate

/**
 * Regresja z produkcji: dodanie usługi w QuickEventModal z ceną brutto 1900,00 zł (23% VAT)
 * zapisywało się jako 1900,01 zł — bo przeliczenie brutto→netto→brutto nie jest tożsamością
 * na siatce groszy. Ustawienia → Usługi tego problemu nie miały, bo ten formularz zawsze
 * wysyła obie kwoty razem (netto i brutto), zamiast każąc backendowi odtwarzać brutto z netta.
 *
 * Te testy dowodzą, że [AppointmentLineItem.create] — silnik cen wspólny dla wszystkich
 * ścieżek tworzenia/edycji rezerwacji — dotrzymuje tej samej zasady: gdy zna dokładne
 * brutto i adjustment jest no-opem, oddaje TO brutto, a nie jego odtworzenie z netta.
 */
class AppointmentLineItemTest {

    private fun create(
        basePriceNet: Long,
        basePriceGross: Long?,
        vatRate: VatRate = VatRate.VAT_23,
        adjustmentType: AdjustmentType = AdjustmentType.PERCENT,
        adjustmentValue: Long = 0L
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

    @Test
    fun `brutto 1900,00 zl przy 23 procent VAT zostaje dokladnie 1900,00, nie 1900,01`() {
        // netto = round(190000 * 100 / 123) = 154472; odtworzenie z netta dałoby 190001.
        val item = create(basePriceNet = 154_472L, basePriceGross = 190_000L)

        assertEquals(190_000L, item.finalPriceGross.amountInCents)
    }

    @Test
    fun `bez podanego brutto - stara sciezka - brutto jest odtwarzane z netta (moze przesunac sie o 1 grosz)`() {
        val item = create(basePriceNet = 154_472L, basePriceGross = null)

        assertEquals(190_001L, item.finalPriceGross.amountInCents)
    }

    @Test
    fun `zaokraglenie w druga strone tez jest zachowywane - 201,00 zl nie zjezdza do 200,99`() {
        // Przykład z komentarzy w kodzie: żadne całe netto nie odwzorowuje się na 201,00
        // przy 23% VAT (163,41 -> 200,99; 163,42 -> 201,01) - stąd dokładne brutto musi
        // być noszone osobno, a nie liczone z netta za każdym razem od nowa.
        val item = create(basePriceNet = 16_341L, basePriceGross = 20_100L)

        assertEquals(20_100L, item.finalPriceGross.amountInCents)
    }

    @Test
    fun `VAT zwolniony (ZW) rowniez zachowuje dokladne brutto - tu brutto i netto sa rowne`() {
        val item = create(basePriceNet = 190_000L, basePriceGross = 190_000L, vatRate = VatRate.VAT_ZW)

        assertEquals(190_000L, item.finalPriceGross.amountInCents)
    }

    @Test
    fun `rabat procentowy inny niz zero nie jest juz no-opem - brutto liczy sie z rabatowanego netta`() {
        // Gdy adjustment faktycznie coś zmienia, nie ma już jednej "kwoty bazowej", którą
        // dałoby się po prostu przepisać - finalNet się zmienia, więc finalGross musi
        // zostać przeliczony z NOWEGO netta, nie ze starego brutto sprzed rabatu.
        // 154472 * 0,9 = 139024,8..., obcięte (nie zaokrąglone) do 139024 - tak liczy
        // calculateFinalNet.
        val item = create(
            basePriceNet = 154_472L,
            basePriceGross = 190_000L,
            adjustmentType = AdjustmentType.PERCENT,
            adjustmentValue = AdjustmentType.convertPercentValueToBasisPoints(-10.0)
        )

        assertEquals(139_024L, item.finalPriceNet.amountInCents)
        assertEquals(VatRate.VAT_23.calculateGrossAmount(item.finalPriceNet).amountInCents, item.finalPriceGross.amountInCents)
    }

    @Test
    fun `SET_GROSS trzyma cel co do grosza, niezaleznie od basePriceGross`() {
        val item = create(basePriceNet = 100_000L, basePriceGross = null, adjustmentType = AdjustmentType.SET_GROSS, adjustmentValue = 123_457L)

        assertEquals(123_457L, item.finalPriceGross.amountInCents)
    }

    @Test
    fun `pozycja z serviceId (katalogowa) tez korzysta z dokladnego brutto katalogu`() {
        val item = AppointmentLineItem.create(
            serviceId = ServiceId.random(),
            serviceName = "Powłoka ceramiczna",
            basePriceNet = Money.fromCents(154_472L),
            vatRate = VatRate.VAT_23,
            adjustmentType = AdjustmentType.PERCENT,
            adjustmentValue = 0L,
            customNote = null,
            basePriceGross = Money.fromCents(190_000L)
        )

        assertEquals(190_000L, item.finalPriceGross.amountInCents)
    }
}
