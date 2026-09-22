package pl.detailing.crm.visitcard.upsell

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visitcard.upsell.infrastructure.VisitUpsellSuggestionEntity
import pl.detailing.crm.visitcard.upsell.infrastructure.VisitUpsellSuggestionRepository
import java.util.UUID

/**
 * Cena propozycji dodatkowej usługi, którą klient widzi na karcie wizyty i którą potem
 * zapłaci. Liczy ją ten sam silnik co pozycję wizyty, z DOKŁADNYM brutto z cennika —
 * wcześniej brutto liczyło się z netta, więc usługa za 1900,00 zł pokazywała 1900,01 zł,
 * a „=Brutto 1900,00" dawało klientowi 1900,01.
 */
class VisitUpsellPricingTest {

    private val visitRepository: VisitRepository = mockk()
    private val serviceRepository: ServiceRepository = mockk()
    private val saved = slot<VisitUpsellSuggestionEntity>()
    private val suggestionRepository: VisitUpsellSuggestionRepository = mockk {
        every { save(capture(saved)) } answers { firstArg() }
    }

    private val service = VisitUpsellAdminService(
        visitRepository, mockk<AppointmentRepository>(), serviceRepository, suggestionRepository, mockk()
    )

    private val studioId = StudioId.random()
    private val visitId = VisitId.random()

    init {
        every { visitRepository.findByIdAndStudioId(visitId.value, studioId.value) } returns mockk<VisitEntity>(relaxed = true)
    }

    /** Usługa z cennika wpisana jako 1900,00 zł brutto: netto 154472 gr, brutto 190000 gr. */
    private fun catalogService(net: Long = 154_472, gross: Long = 190_000, vat: Int = 23): String {
        val id = UUID.randomUUID()
        every { serviceRepository.findByIdAndStudioId(id, studioId.value) } returns mockk<ServiceEntity>(relaxed = true).also {
            every { it.id } returns id
            every { it.name } returns "Powłoka ceramiczna"
            every { it.isActive } returns true
            every { it.basePriceNet } returns net
            every { it.basePriceGross } returns gross
            every { it.vatRate } returns vat
        }
        return id.toString()
    }

    private fun suggest(serviceId: String, adjustment: UpsellAdjustment? = null) =
        service.create(visitId, studioId, UserId.random(), CreateUpsellSuggestionRequest(serviceId, adjustment))

    @Test
    fun `bez rabatu klient widzi cene z cennika 1900,00 zl, nie 1900,01 zl`() {
        val response = suggest(catalogService())

        assertEquals(190_000L, response.finalPriceGross)
        assertEquals(154_472L, response.finalPriceNet)
        assertEquals(190_000L, response.originalPriceGross)
        assertEquals(190_000L, saved.captured.basePriceGross)
    }

    @Test
    fun `ustawiona cena brutto 1900,00 zl zostaje 1900,00 zl`() {
        val response = suggest(
            catalogService(net = 203_252, gross = 250_000),
            UpsellAdjustment(AdjustmentType.SET_GROSS, 190_000.0)
        )

        assertEquals(190_000L, response.finalPriceGross)
        assertEquals(154_472L, response.finalPriceNet)
        assertEquals(250_000L, response.originalPriceGross)
    }

    @Test
    fun `upust brutto 100 zl od 1900,00 zl daje 1800,00 zl`() {
        val response = suggest(catalogService(), UpsellAdjustment(AdjustmentType.FIXED_GROSS, 10_000.0))

        assertEquals(180_000L, response.finalPriceGross)
        assertEquals(146_341L, response.finalPriceNet) // round(180000 · 100 / 123)
        assertEquals(190_000L, response.originalPriceGross)
    }

    @Test
    fun `rabat procentowy liczy brutto z netta po rabacie`() {
        val response = suggest(catalogService(), UpsellAdjustment(AdjustmentType.PERCENT, -10.0))

        assertEquals(139_025L, response.finalPriceNet) // 154472 − round(15447,2)
        assertEquals(171_001L, response.finalPriceGross) // 139025 + round(31975,75)
        assertEquals(190_000L, response.originalPriceGross)
    }

    @Test
    fun `usluga z cena od netta liczy brutto z netta`() {
        val response = suggest(catalogService(net = 100_000, gross = 123_000))

        assertEquals(123_000L, response.finalPriceGross)
        assertEquals(123_000L, response.originalPriceGross)
    }
}
