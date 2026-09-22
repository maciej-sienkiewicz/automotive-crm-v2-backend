package pl.detailing.crm.visit.infrastructure

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.VatRate
import pl.detailing.crm.visit.domain.VisitServiceItem
import java.util.UUID

/**
 * Dokładne brutto pozycji wizyty przez zapis do bazy i odczyt — także w snapshocie ceny
 * sprzed edycji, z którego „Wycofaj zmianę" przywraca pozycję. Zgubione na tej granicy
 * nie odtworzy się już nigdy: 154472 gr netto daje z powrotem 1900,01 zł, nie 1900,00 zł.
 */
class VisitServiceItemEntityGrossTest {

    private val visit = mockk<VisitEntity>(relaxed = true)

    private fun item(basePriceGross: Long?) = VisitServiceItem.createPending(
        serviceId = null,
        serviceName = "Powłoka ceramiczna",
        basePriceNet = Money(154_472),
        vatRate = VatRate.VAT_23,
        adjustmentType = AdjustmentType.PERCENT,
        adjustmentValue = 0,
        customNote = null,
        basePriceGross = basePriceGross?.let { Money(it) }
    ).approve()!!

    @Test
    fun `brutto bazowe przechodzi przez zapis i odczyt`() {
        val restored = VisitServiceItemEntity.fromDomain(item(190_000), visit).toDomain()

        assertEquals(Money(190_000), restored.basePriceGross)
        assertEquals(190_000L, restored.finalPriceGross.amountInCents)
    }

    @Test
    fun `pozycja bez brutto bazowego zostaje bez niego`() {
        val restored = VisitServiceItemEntity.fromDomain(item(null), visit).toDomain()

        assertNull(restored.basePriceGross)
    }

    @Test
    fun `snapshot ceny sprzed edycji pamieta brutto i wycofanie zmiany wraca do 1900,00 zl`() {
        val edited = item(190_000).toPending(newBasePriceNet = Money(100_000))

        val restored = VisitServiceItemEntity.fromDomain(edited, visit).toDomain()

        assertEquals(Money(190_000), restored.confirmedSnapshot?.basePriceGross)
        val rolledBack = restored.reject()!!
        assertEquals(190_000L, rolledBack.finalPriceGross.amountInCents)
        assertEquals(Money(190_000), rolledBack.basePriceGross)
    }

    @Test
    fun `snapshot zapisany przed dodaniem pola czyta sie jako cena od netta`() {
        val legacy = VisitServiceItemEntity.fromDomain(item(null).toPending(newBasePriceNet = Money(100_000)), visit)
        legacy.confirmedSnapshot = """
            {"basePriceNet":154472,"vatRate":23,"adjustmentType":"PERCENT","adjustmentValue":0,
             "finalPriceNet":154472,"finalPriceGross":190001,"customNote":null}
        """.trimIndent()

        val snapshot = legacy.toDomain().confirmedSnapshot!!

        assertNull(snapshot.basePriceGross)
        assertEquals(190_001L, snapshot.finalPriceGross.amountInCents)
    }

    @Test
    fun `kolumna base_price_gross jest opcjonalna dla starych wierszy`() {
        val entity = VisitServiceItemEntity(
            id = UUID.randomUUID(), visit = visit, serviceId = null, serviceName = "Stara pozycja",
            basePriceNet = 154_472, vatRate = 23, adjustmentType = AdjustmentType.PERCENT, adjustmentValue = 0,
            finalPriceNet = 154_472, finalPriceGross = 190_000,
            status = pl.detailing.crm.shared.VisitServiceStatus.CONFIRMED,
            pendingOperation = null, confirmedSnapshot = null, customNote = null, confirmedAt = null, pendingAt = null
        )

        assertNull(entity.toDomain().basePriceGross)
        assertEquals(190_000L, entity.toDomain().finalPriceGross.amountInCents)
    }
}
