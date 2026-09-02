package pl.detailing.crm.visit.services

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.visit.domain.VisitFixtures
import java.util.UUID

/**
 * Cross-tenant reference + Parameter Tampering — pozycje usług wizyty.
 *
 *  - `serviceId` cudzego studia: wcześniej `findAllById` zwracał obcy rekord (stawka VAT,
 *    cena brutto) i zapisywał referencję między tenantami; teraz lookup jest studio-scoped.
 *  - ujemna cena / absurdalna korekta: wcześniej 500 (`Money`) lub cicho zapisana kwota.
 */
class ServicesChangePlannerSecurityTest {

    private val serviceRepository = mockk<ServiceRepository>()
    private val planner = ServicesChangePlanner(serviceRepository)
    private val studioA = StudioId.random()
    private val visit = VisitFixtures.visit(studioId = studioA)

    private fun payload(added: List<AddedService> = emptyList(), updated: List<UpdatedService> = emptyList()) =
        ServicesChangesPayload(notifyCustomer = false, requireConfirmation = false, added = added, updated = updated, deleted = emptyList())

    @Test
    fun `serviceId from another studio is not found and the unscoped lookup is never used`() {
        val foreignServiceId = UUID.randomUUID()
        every { serviceRepository.findAllByIdInAndStudioId(listOf(foreignServiceId), studioA.value) } returns emptyList()

        assertThrows<EntityNotFoundException> {
            planner.plan(visit, payload(added = listOf(
                AddedService(serviceId = foreignServiceId.toString(), serviceName = "Cudza", basePriceNet = 100, vatRate = 23, adjustment = null, note = null)
            )))
        }

        verify(exactly = 0) { serviceRepository.findAllById(any<Iterable<UUID>>()) }
        verify(exactly = 1) { serviceRepository.findAllByIdInAndStudioId(listOf(foreignServiceId), studioA.value) }
    }

    @Test
    fun `negative net price is a validation error`() {
        assertThrows<ValidationException> {
            planner.plan(visit, payload(added = listOf(
                AddedService(serviceId = null, serviceName = "Gratis?", basePriceNet = -100_000, vatRate = 23, adjustment = null, note = null)
            )))
        }
    }

    @Test
    fun `repricing an existing line to a negative value is a validation error`() {
        val lineId = visit.serviceItems.first().id.value.toString()
        assertThrows<ValidationException> {
            planner.plan(visit, payload(updated = listOf(UpdatedService(serviceLineItemId = lineId, basePriceNet = -1))))
        }
    }

    @Test
    fun `percent adjustment outside -100 to 1000 is a validation error`() {
        listOf(-150.0, 5000.0, Double.NaN).forEach { pct ->
            assertThrows<ValidationException>("pct=$pct") {
                planner.plan(visit, payload(added = listOf(
                    AddedService(serviceId = null, serviceName = "X", basePriceNet = 100, vatRate = 23,
                        adjustment = ServiceAdjustment(AdjustmentType.PERCENT, pct), note = null)
                )))
            }
        }
    }

    @Test
    fun `absurd price is rejected`() {
        assertThrows<ValidationException> {
            planner.plan(visit, payload(added = listOf(
                AddedService(serviceId = null, serviceName = "X", basePriceNet = Long.MAX_VALUE, vatRate = 23, adjustment = null, note = null)
            )))
        }
    }
}
