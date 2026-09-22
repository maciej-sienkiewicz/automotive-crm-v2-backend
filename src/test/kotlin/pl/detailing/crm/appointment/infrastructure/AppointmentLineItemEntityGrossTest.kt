package pl.detailing.crm.appointment.infrastructure

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.domain.AppointmentLineItem
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.VatRate

/**
 * Dokładne brutto pozycji rezerwacji przez zapis do bazy i odczyt. Z rezerwacji jedzie ono
 * dalej na przyjęcie pojazdu — zgubione tutaj wraca na wizycie jako 1900,01 zł.
 */
class AppointmentLineItemEntityGrossTest {

    private val appointment = mockk<AppointmentEntity>(relaxed = true)

    private fun line(basePriceGross: Long?, type: AdjustmentType = AdjustmentType.PERCENT, value: Long = 0) =
        AppointmentLineItem.create(
            serviceId = null,
            serviceName = "Powłoka ceramiczna",
            basePriceNet = Money(154_472),
            vatRate = VatRate.VAT_23,
            adjustmentType = type,
            adjustmentValue = value,
            customNote = null,
            basePriceGross = basePriceGross?.let { Money(it) }
        )

    @Test
    fun `brutto bazowe i koncowe przechodza przez zapis i odczyt`() {
        val restored = AppointmentLineItemEntity.fromDomain(line(190_000), appointment).toDomain()

        assertEquals(Money(190_000), restored.basePriceGross)
        assertEquals(190_000L, restored.finalPriceGross.amountInCents)
    }

    @Test
    fun `upust brutto zapisany na rezerwacji wraca jako 1800,00 zl`() {
        val restored = AppointmentLineItemEntity.fromDomain(line(190_000, AdjustmentType.FIXED_GROSS, 10_000), appointment).toDomain()

        assertEquals(180_000L, restored.finalPriceGross.amountInCents)
        assertEquals(Money(190_000), restored.basePriceGross)
    }

    @Test
    fun `pozycja z cena od netta nie dostaje zmyslonego brutto`() {
        val restored = AppointmentLineItemEntity.fromDomain(line(null), appointment).toDomain()

        assertNull(restored.basePriceGross)
        assertEquals(190_001L, restored.finalPriceGross.amountInCents)
    }
}
