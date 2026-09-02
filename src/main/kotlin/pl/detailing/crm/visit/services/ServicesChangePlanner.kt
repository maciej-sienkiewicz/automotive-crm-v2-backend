package pl.detailing.crm.visit.services

import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VatRate
import pl.detailing.crm.visit.domain.Visit
import pl.detailing.crm.visit.domain.VisitServiceItem

/**
 * Result of translating a [ServicesChangesPayload] into domain items.
 *
 * [deleted] items are modelled as updates carrying a DELETE pending operation —
 * nothing is physically removed from the visit.
 */
data class PlannedServicesChange(
    val added: List<VisitServiceItem>,
    val updated: List<VisitServiceItem>,
    val deleted: List<VisitServiceItem>
)

/**
 * Turns the wire payload into domain service items and projects the resulting visit.
 *
 * Shared by [SaveVisitServicesHandler] (which persists the projection) and by the
 * SMS-draft endpoint (which only reads it), so the price the customer is told about
 * is computed exactly the same way as the price that later gets saved.
 */
@Service
class ServicesChangePlanner(
    private val serviceRepository: ServiceRepository
) {
    companion object {
        /** 10 000 000 PLN netto w groszach — żadna usługa detailingowa nie kosztuje więcej. */
        const val MAX_BASE_PRICE_NET_CENTS = 1_000_000_000L
    }

    fun plan(visit: Visit, payload: ServicesChangesPayload): PlannedServicesChange {
        val serviceIds = payload.added.mapNotNull { it.serviceId?.let { id -> ServiceId.fromString(id) } }
        // Studio-scoped: a serviceId from another studio must resolve to "not found",
        // never to that studio's VAT rate / gross price or a cross-tenant reference.
        val servicesFromDb = if (serviceIds.isNotEmpty()) {
            serviceRepository.findAllByIdInAndStudioId(serviceIds.map { it.value }, visit.studioId.value)
                .associateBy { it.id }
        } else emptyMap()

        val addedItems = payload.added.map { added ->
            requireValidPrice(added.basePriceNet)
            added.adjustment?.let { requireValidAdjustment(it) }
            val adjustmentType = added.adjustment?.type ?: AdjustmentType.PERCENT
            val adjustmentValue = added.adjustment?.value ?: 0.0

            val adjustmentValueLong = when (adjustmentType) {
                AdjustmentType.PERCENT -> AdjustmentType.convertPercentValueToBasisPoints(adjustmentValue)
                else -> adjustmentValue.toLong()
            }

            val serviceId = added.serviceId?.let { ServiceId.fromString(it) }
            val vatRate = if (serviceId != null) {
                val dbService = servicesFromDb[serviceId.value]
                    ?: throw EntityNotFoundException("Usługa o ID '${serviceId.value}' nie została znaleziona")
                VatRate.fromInt(dbService.vatRate)
            } else {
                VatRate.fromInt(added.vatRate)
            }

            // Catalog's stored gross applies only when the item is added at the catalog net
            // price — otherwise (custom/edited base) gross is derived from net as before.
            val basePriceGross = serviceId
                ?.let { servicesFromDb[it.value] }
                ?.takeIf { it.basePriceNet == added.basePriceNet }
                ?.let { Money(it.basePriceGross) }

            VisitServiceItem.createPending(
                serviceId = serviceId,
                serviceName = added.serviceName,
                basePriceNet = Money(added.basePriceNet),
                vatRate = vatRate,
                adjustmentType = adjustmentType,
                adjustmentValue = adjustmentValueLong,
                customNote = added.note,
                basePriceGross = basePriceGross
            )
        }

        val updatedItems = payload.updated.map { updated ->
            requireValidPrice(updated.basePriceNet)
            updated.adjustment?.let { requireValidAdjustment(it) }
            val existingItem = visit.serviceItems.find { it.id.value.toString() == updated.serviceLineItemId }
                ?: throw EntityNotFoundException("Service item ${updated.serviceLineItemId} not found in visit ${visit.id}")

            val newAdjustmentType = updated.adjustment?.type
            val newAdjustmentValue = updated.adjustment?.let { adj ->
                when (adj.type) {
                    AdjustmentType.PERCENT -> AdjustmentType.convertPercentValueToBasisPoints(adj.value)
                    else -> adj.value.toLong()
                }
            }

            val newVatRate = updated.vatRate?.let { VatRate.fromInt(it) }
            existingItem.toPending(Money(updated.basePriceNet), newAdjustmentType, newAdjustmentValue, newVatRate)
        }

        val deletedItems = payload.deleted.map { deleted ->
            val existingItem = visit.serviceItems.find { it.id.value.toString() == deleted.serviceLineItemId }
                ?: throw EntityNotFoundException("Service item ${deleted.serviceLineItemId} not found in visit ${visit.id}")

            existingItem.markForDeletion()
        }

        return PlannedServicesChange(added = addedItems, updated = updatedItems, deleted = deletedItems)
    }

    /**
     * Input bounds for client-supplied money. A negative base used to surface as an
     * `IllegalArgumentException` from `Money` (HTTP 500); an absurd value silently
     * became the visit total.
     */
    private fun requireValidPrice(basePriceNet: Long) {
        if (basePriceNet < 0) throw ValidationException("Cena netto nie może być ujemna")
        if (basePriceNet > MAX_BASE_PRICE_NET_CENTS) throw ValidationException("Cena netto przekracza dopuszczalny limit")
    }

    private fun requireValidAdjustment(adjustment: ServiceAdjustment) {
        if (adjustment.value.isNaN() || adjustment.value.isInfinite()) {
            throw ValidationException("Nieprawidłowa wartość korekty ceny")
        }
        when (adjustment.type) {
            // Rabat do -100 % (gratis), narzut do +1000 % — wszystko poza tym to pomyłka albo manipulacja.
            AdjustmentType.PERCENT ->
                if (adjustment.value < -100.0 || adjustment.value > 1000.0) {
                    throw ValidationException("Korekta procentowa musi mieścić się w zakresie od -100 do 1000")
                }
            AdjustmentType.FIXED_NET, AdjustmentType.FIXED_GROSS ->
                if (Math.abs(adjustment.value) > MAX_BASE_PRICE_NET_CENTS) {
                    throw ValidationException("Korekta kwotowa przekracza dopuszczalny limit")
                }
            AdjustmentType.SET_NET, AdjustmentType.SET_GROSS ->
                if (adjustment.value < 0 || adjustment.value > MAX_BASE_PRICE_NET_CENTS) {
                    throw ValidationException("Ustawiona cena musi być nieujemna i mieścić się w limicie")
                }
        }
    }

    /** Applies the plan to [visit] and returns the projected visit (nothing is persisted here). */
    fun project(visit: Visit, plan: PlannedServicesChange, userId: UserId): Visit =
        visit.saveServicesChanges(
            added = plan.added,
            updated = plan.updated + plan.deleted,
            deletedIds = emptyList(),
            updatedBy = userId
        )
}
