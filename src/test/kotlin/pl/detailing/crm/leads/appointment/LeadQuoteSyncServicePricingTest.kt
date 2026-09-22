package pl.detailing.crm.leads.appointment

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentLineItemEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadServiceItemEntity
import pl.detailing.crm.leads.infrastructure.LeadServiceItemRepository
import java.util.UUID

/**
 * Wycena leada → pozycje rezerwacji. Wycena leada trzyma BRUTTO uzgodnione z klientem;
 * rezerwacja dostaje je jako dokładne brutto, a netto liczy się z niego. Własne dzielenie
 * przez 1,23 w double albo odtworzenie brutto z netta dawało 1900,01 zł zamiast 1900,00 zł.
 */
class LeadQuoteSyncServicePricingTest {

    private val studioId = UUID.randomUUID()
    private val leadId = UUID.randomUUID()
    private val appointmentId = UUID.randomUUID()

    private val lineItems = mutableListOf<AppointmentLineItemEntity>()
    private val appointment = mockk<AppointmentEntity>(relaxed = true) {
        every { status } returns AppointmentStatus.CREATED
        every { lineItems } returns this@LeadQuoteSyncServicePricingTest.lineItems
    }
    private val lead = mockk<LeadEntity>(relaxed = true) {
        every { id } returns leadId
        every { studioId } returns this@LeadQuoteSyncServicePricingTest.studioId
        every { appointmentId } returns this@LeadQuoteSyncServicePricingTest.appointmentId
    }

    private val leadItemRepository = mockk<LeadServiceItemRepository>()
    private val appointmentRepository = mockk<AppointmentRepository> {
        every { findByIdAndStudioId(appointmentId, studioId) } returns appointment
        every { save(any()) } answers { firstArg() }
    }

    private val service = LeadQuoteSyncService(
        mockk<LeadRepository>(), leadItemRepository, appointmentRepository, mockk(relaxed = true)
    )

    private fun quoted(priceGross: Long, priceNet: Long? = null, vatRate: Int? = 23, quantity: Int = 1) =
        LeadServiceItemEntity(
            id = UUID.randomUUID(), studioId = studioId, leadId = leadId, serviceId = null,
            name = "Powłoka ceramiczna", priceGross = priceGross, priceNet = priceNet,
            vatRate = vatRate, quantity = quantity
        )

    private fun push(vararg items: LeadServiceItemEntity) {
        every { leadItemRepository.findByLeadIdOrderByCreatedAtAsc(leadId) } returns items.toList()
        service.pushToAppointment(lead)
    }

    @Test
    fun `brutto z wyceny 1900,00 zl trafia na rezerwacje jako 1900,00 zl`() {
        push(quoted(priceGross = 190_000))

        val line = lineItems.single()
        assertEquals(154_472L, line.basePriceNet)
        assertEquals(190_000L, line.basePriceGross)
        assertEquals(190_000L, line.finalPriceGross)
        assertEquals(154_472L, line.finalPriceNet)
    }

    @Test
    fun `zapisane netto zgodne z brutto co do grosza zostaje`() {
        push(quoted(priceGross = 190_000, priceNet = 154_471))

        assertEquals(154_471L, lineItems.single().basePriceNet)
        assertEquals(190_000L, lineItems.single().finalPriceGross)
    }

    @Test
    fun `niezgodne netto ustepuje brutto - brutto jest wiodace`() {
        push(quoted(priceGross = 190_000, priceNet = 150_000))

        assertEquals(154_472L, lineItems.single().basePriceNet)
        assertEquals(190_000L, lineItems.single().finalPriceGross)
    }

    @Test
    fun `stawka 8 procent - netto z brutto przy 8 procent`() {
        push(quoted(priceGross = 110_000, vatRate = 8))

        val line = lineItems.single()
        assertEquals(101_852L, line.basePriceNet) // round(110000 · 100 / 108)
        assertEquals(110_000L, line.finalPriceGross)
    }

    @Test
    fun `ilosc rozwija sie na wiersze z tym samym dokladnym brutto`() {
        push(quoted(priceGross = 190_000, quantity = 2))

        assertEquals(listOf(190_000L, 190_000L), lineItems.map { it.finalPriceGross })
    }

    @Test
    fun `stawka spoza slownika zatrzymuje synchronizacje zamiast zmieniac VAT po cichu`() {
        push(quoted(priceGross = 190_000, vatRate = 7))

        assertEquals(emptyList<AppointmentLineItemEntity>(), lineItems)
        verify(exactly = 0) { appointmentRepository.save(any()) }
    }
}
