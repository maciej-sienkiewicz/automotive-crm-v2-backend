package pl.detailing.crm.visit.services

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VatRate
import pl.detailing.crm.visit.domain.VisitFixtures
import pl.detailing.crm.visit.domain.VisitServiceItem
import java.util.UUID

/**
 * Edycja usług na stronie wizyty zachowuje brutto wpisane przez człowieka (CLAUDE.md §1).
 *
 * Regresja z produkcji: payload edycji w ogóle nie miał pola brutto, front zamieniał wpisane
 * brutto na netto jeszcze w przeglądarce, a pozycja odtwarzała brutto z netta — usługa za
 * 1900,00 zł po „powrocie do ceny katalogowej" albo zmianie rabatu kosztowała 1900,01 zł.
 */
class ServicesChangePlannerGrossTest {

    private val serviceRepository = mockk<ServiceRepository>()
    private val planner = ServicesChangePlanner(serviceRepository)
    private val studio = StudioId.random()

    private fun payload(added: List<AddedService> = emptyList(), updated: List<UpdatedService> = emptyList()) =
        ServicesChangesPayload(notifyCustomer = false, requireConfirmation = false, added = added, updated = updated, deleted = emptyList())

    private fun confirmedItem(
        basePriceNet: Long,
        basePriceGross: Long?,
        adjustmentType: AdjustmentType = AdjustmentType.PERCENT,
        adjustmentValue: Long = 0
    ): VisitServiceItem = VisitServiceItem.createPending(
        serviceId = null,
        serviceName = "Powłoka ceramiczna",
        basePriceNet = Money(basePriceNet),
        vatRate = VatRate.VAT_23,
        adjustmentType = adjustmentType,
        adjustmentValue = adjustmentValue,
        customNote = null,
        basePriceGross = basePriceGross?.let { Money(it) }
    ).approve()!!

    private fun added(
        basePriceNet: Long,
        basePriceGross: Long? = null,
        serviceId: UUID? = null,
        adjustment: ServiceAdjustment? = null
    ) = AddedService(
        serviceId = serviceId?.toString(),
        serviceName = "Powłoka ceramiczna",
        basePriceNet = basePriceNet,
        vatRate = 23,
        adjustment = adjustment,
        note = null,
        basePriceGross = basePriceGross
    )

    // ── Dodawanie ───────────────────────────────────────────────────────────────

    @Test
    fun `dodana usluga z brutto wpisanym przez uzytkownika kosztuje dokladnie 1900,00`() {
        val plan = planner.plan(VisitFixtures.visit(studio), payload(added = listOf(added(154_472, basePriceGross = 190_000))))

        val item = plan.added.single()
        assertEquals(190_000L, item.finalPriceGross.amountInCents)
        assertEquals(Money(190_000), item.basePriceGross)
    }

    @Test
    fun `dodana usluga katalogowa bez brutto w zadaniu dostaje brutto z katalogu`() {
        val serviceId = UUID.randomUUID()
        every { serviceRepository.findAllByIdInAndStudioId(listOf(serviceId), studio.value) } returns listOf(
            ServiceEntity(
                id = serviceId, studioId = studio.value, name = "Powłoka ceramiczna",
                basePriceNet = 154_472, basePriceGross = 190_000, vatRate = 23,
                replacesServiceId = null, createdBy = UUID.randomUUID(), updatedBy = UUID.randomUUID()
            )
        )

        val plan = planner.plan(VisitFixtures.visit(studio), payload(added = listOf(added(154_472, serviceId = serviceId))))

        assertEquals(190_000L, plan.added.single().finalPriceGross.amountInCents)
    }

    @Test
    fun `dodana usluga z cena od netta liczy brutto z netta`() {
        val plan = planner.plan(VisitFixtures.visit(studio), payload(added = listOf(added(154_472))))

        assertEquals(190_001L, plan.added.single().finalPriceGross.amountInCents)
    }

    @Test
    fun `brutto niespojne z netto to blad walidacji`() {
        assertThrows<ValidationException> {
            planner.plan(VisitFixtures.visit(studio), payload(added = listOf(added(154_472, basePriceGross = 200_000))))
        }
    }

    @Test
    fun `rabat kwotowy od netta z dodatnia wartoscia obniza cene`() {
        val plan = planner.plan(
            VisitFixtures.visit(studio),
            payload(added = listOf(added(10_000, adjustment = ServiceAdjustment(AdjustmentType.FIXED_NET, 1_667.0))))
        )

        val item = plan.added.single()
        assertEquals(8_333L, item.finalPriceNet.amountInCents)
        assertEquals(10_250L, item.finalPriceGross.amountInCents)
    }

    // ── Edycja ──────────────────────────────────────────────────────────────────

    @Test
    fun `cofniecie rabatu do zera na pozycji od brutto wraca do 1900,00`() {
        val item = confirmedItem(154_472, 190_000, AdjustmentType.PERCENT, -1_000)
        val visit = VisitFixtures.visit(studio, items = listOf(item))

        val plan = planner.plan(visit, payload(updated = listOf(
            UpdatedService(
                serviceLineItemId = item.id.value.toString(),
                basePriceNet = 154_472,
                adjustment = ServiceAdjustment(AdjustmentType.PERCENT, 0.0)
            )
        )))

        assertEquals(190_000L, plan.updated.single().finalPriceGross.amountInCents)
    }

    @Test
    fun `nowe brutto wpisane na wizycie zostaje dokladnie`() {
        val item = confirmedItem(100_000, null)
        val visit = VisitFixtures.visit(studio, items = listOf(item))

        val plan = planner.plan(visit, payload(updated = listOf(
            UpdatedService(serviceLineItemId = item.id.value.toString(), basePriceNet = 154_472, basePriceGross = 190_000)
        )))

        assertEquals(190_000L, plan.updated.single().finalPriceGross.amountInCents)
    }

    @Test
    fun `zmiana stawki VAT z zachowaniem wpisanego brutto`() {
        val item = confirmedItem(154_472, 190_000)
        val visit = VisitFixtures.visit(studio, items = listOf(item))

        val plan = planner.plan(visit, payload(updated = listOf(
            UpdatedService(
                serviceLineItemId = item.id.value.toString(),
                basePriceNet = 175_926,
                vatRate = 8,
                basePriceGross = 190_000
            )
        )))

        val edited = plan.updated.single()
        assertEquals(190_000L, edited.finalPriceGross.amountInCents)
        assertEquals(175_926L, edited.finalPriceNet.amountInCents)
    }

    @Test
    fun `niespojne brutto przy edycji to blad walidacji`() {
        val item = confirmedItem(154_472, 190_000)
        val visit = VisitFixtures.visit(studio, items = listOf(item))

        assertThrows<ValidationException> {
            planner.plan(visit, payload(updated = listOf(
                UpdatedService(serviceLineItemId = item.id.value.toString(), basePriceNet = 154_472, basePriceGross = 150_000)
            )))
        }
    }
}
