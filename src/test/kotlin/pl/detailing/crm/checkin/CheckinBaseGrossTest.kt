package pl.detailing.crm.checkin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.domain.AppointmentLineItem
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VatRate
import java.util.UUID

/**
 * Regresja z produkcji: check-in zapisywał na wizycie usługę za 1900,00 zł jako 1900,01 zł,
 * bo gdy kreator nie przysłał brutto, odtwarzał je z netta (154472 gr → 190001 gr).
 * Teraz brutto pochodzi z żądania, z rezerwacji albo z katalogu — w tej kolejności.
 */
class CheckinBaseGrossTest {

    private val serviceId = UUID.randomUUID()

    private fun request(
        basePriceNet: Long = 154_472,
        basePriceGross: Long? = null,
        vatRate: Int = 23,
        serviceId: UUID? = this.serviceId,
        serviceName: String = "Powłoka ceramiczna"
    ) = ServiceLineItemRequest(
        id = "line-1",
        serviceId = serviceId?.toString(),
        serviceName = serviceName,
        basePriceNet = basePriceNet,
        basePriceGross = basePriceGross,
        vatRate = vatRate,
        adjustment = AdjustmentRequest(type = "PERCENT", value = 0.0),
        note = null
    )

    private fun reservationItem(
        basePriceNet: Long = 154_472,
        basePriceGross: Long? = 190_000,
        serviceId: UUID? = this.serviceId,
        serviceName: String = "Powłoka ceramiczna"
    ) = AppointmentLineItem.create(
        serviceId = serviceId?.let { ServiceId(it) },
        serviceName = serviceName,
        basePriceNet = Money(basePriceNet),
        vatRate = VatRate.VAT_23,
        adjustmentType = AdjustmentType.PERCENT,
        adjustmentValue = 0,
        customNote = null,
        basePriceGross = basePriceGross?.let { Money(it) }
    )

    private val catalog = mapOf(serviceId to CatalogPrice(basePriceNet = 154_472, basePriceGross = 190_000, vatRate = 23))

    @Test
    fun `brutto przyslane przez kreator wygrywa`() {
        val gross = resolveCheckinBaseGross(request(basePriceGross = 190_000), emptyList(), emptyMap())

        assertEquals(Money(190_000), gross)
    }

    @Test
    fun `bez brutto w zadaniu bierze brutto zapisane przy usludze w rezerwacji`() {
        val gross = resolveCheckinBaseGross(request(), listOf(reservationItem()), emptyMap())

        assertEquals(Money(190_000), gross)
    }

    @Test
    fun `bez brutto w zadaniu i rezerwacji bierze brutto z katalogu`() {
        val gross = resolveCheckinBaseGross(request(), emptyList(), catalog)

        assertEquals(Money(190_000), gross)
    }

    @Test
    fun `zmieniona w kreatorze cena bazowa nie dziedziczy brutto rezerwacji ani katalogu`() {
        // cena podniesiona od strony netta — stare brutto przestało obowiązywać
        val gross = resolveCheckinBaseGross(request(basePriceNet = 200_000), listOf(reservationItem()), catalog)

        assertNull(gross)
    }

    @Test
    fun `zmieniona stawka VAT nie dziedziczy brutto katalogu`() {
        val gross = resolveCheckinBaseGross(request(vatRate = 8), emptyList(), catalog)

        assertNull(gross)
    }

    @Test
    fun `pozycja rezerwacji innej uslugi o tej samej cenie nie jest zrodlem brutto`() {
        val otherService = reservationItem(serviceId = UUID.randomUUID(), serviceName = "Inna usługa")

        val gross = resolveCheckinBaseGross(request(serviceId = null, serviceName = "Usługa własna"), listOf(otherService), emptyMap())

        assertNull(gross)
    }

    @Test
    fun `pozycja rezerwacji z cena od netta nie podaje brutto`() {
        val netSide = reservationItem(basePriceGross = null)

        val gross = resolveCheckinBaseGross(request(), listOf(netSide), emptyMap())

        assertNull(gross)
    }

    @Test
    fun `brutto odbiegajace od netta o wiecej niz grosz to blad walidacji, nie 500`() {
        assertThrows<ValidationException> {
            resolveCheckinBaseGross(request(basePriceGross = 190_500), emptyList(), emptyMap())
        }
    }

    @Test
    fun `brutto rozne od wyliczonego o grosz jest poprawne - to cena wpisana od brutto`() {
        // 154472 netto → wyliczone 190001; wpisane 190000 mieści się w groszu zaokrąglenia „w stu"
        val gross = resolveCheckinBaseGross(request(basePriceGross = 190_000), emptyList(), emptyMap())

        assertEquals(Money(190_000), gross)
    }

    @Test
    fun `pozycja wizyty z check-inu zachowuje 1900,00 przy rabacie zerowym`() {
        val baseGross = resolveCheckinBaseGross(request(), listOf(reservationItem()), emptyMap())

        val lineItem = AppointmentLineItem.create(
            serviceId = ServiceId(serviceId),
            serviceName = "Powłoka ceramiczna",
            basePriceNet = Money(154_472),
            vatRate = VatRate.VAT_23,
            adjustmentType = AdjustmentType.PERCENT,
            adjustmentValue = 0,
            customNote = null,
            basePriceGross = baseGross
        )

        assertEquals(190_000L, lineItem.finalPriceGross.amountInCents)
        assertEquals(Money(190_000), lineItem.basePriceGross)
    }
}
