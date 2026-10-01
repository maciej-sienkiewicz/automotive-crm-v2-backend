package pl.detailing.crm.subscription.pricing

import org.springframework.stereotype.Service
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.management.PendingPlanChangeRepository
import pl.detailing.crm.subscription.management.PendingPlanChangeStatus
import pl.detailing.crm.subscription.entitlement.AddOnPriceLineDto
import pl.detailing.crm.subscription.entitlement.CustomPriceResponse
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import pl.detailing.crm.subscription.entitlement.infrastructure.AddOnJpaRepository
import pl.detailing.crm.subscription.entitlement.infrastructure.PlanJpaRepository

/**
 * Computes the total monthly price for a custom plan (BASIC + selected add-ons).
 *
 * When any add-on in the selection has a null price (not yet launched),
 * [CustomPriceResponse.hasUndefinedPrices] is true and
 * [CustomPriceResponse.totalMonthlyPriceCents] is null — the frontend
 * should show "Cena do ustalenia" for those items.
 */
@Service
class PricingService(
    private val planRepository: PlanJpaRepository,
    private val addOnRepository: AddOnJpaRepository,
    private val entitlementService: EntitlementService,
    private val pendingPlanChangeRepository: PendingPlanChangeRepository
) {

    /** Plan i moduły kolejnego okresu z ceną — to, za co płaci odnowienie. */
    data class NextPeriodPrice(
        val planKey: PlanKey,
        val planName: String,
        val addOnKeys: List<AddOnKey>,
        val amountCents: Long
    )

    /**
     * Cena KOLEJNEGO okresu: plan po zaplanowanym downgradzie (jeśli jest) i moduły bez
     * zaplanowanego wyłączenia.
     *
     * Wcześniej odnowienie liczyło cenę bieżącego planu. Downgrade zaplanowany na koniec
     * okresu wchodził na początku właśnie opłaconego okresu — klient płacił 299 zł za FULL
     * i dostawał BASIC (audyt, S1). Tę samą liczbę pokazuje `my-plan.nextRenewalCostCents`,
     * więc przycisk i bramka płatności mówią to samo.
     */
    fun nextPeriodPrice(studioId: StudioId): NextPeriodPrice {
        val entitlements = entitlementService.readCurrent(studioId)
        val pending = pendingPlanChangeRepository.findByStudioIdAndStatus(studioId.value, PendingPlanChangeStatus.PENDING)
        val planKey = pending?.toPlanKey ?: entitlements.planKey
        val plan = planRepository.findByKey(planKey)
            ?: throw EntityNotFoundException("Plan nie został znaleziony: $planKey")

        // Downgrade zmienia plan, a zmiana planu czyści moduły — po nim w kolejnym okresie
        // nie zostaje żaden z obecnych.
        val renewingAddOnKeys = if (pending != null) emptyList()
        else entitlements.activeAddOnKeys.filterNot { it in entitlements.addOnCancellations.keys }.sortedBy { it.name }
        val addOns = if (renewingAddOnKeys.isEmpty()) emptyList() else addOnRepository.findAllByKeyIn(renewingAddOnKeys)

        return NextPeriodPrice(
            planKey = planKey,
            planName = plan.name,
            addOnKeys = renewingAddOnKeys,
            amountCents = plan.monthlyPriceGrossCents + addOns.sumOf { it.monthlyPriceGrossCents ?: 0L }
        )
    }

    fun calculateCustomPrice(addOnKeys: List<AddOnKey>): CustomPriceResponse {
        val basePlan = planRepository.findByKey(PlanKey.BASIC)
            ?: throw EntityNotFoundException("Plan BASIC nie został znaleziony w katalogu")

        val addOnEntities = if (addOnKeys.isEmpty()) emptyList()
        else addOnRepository.findAllByKeyIn(addOnKeys)

        val addOnLines = addOnEntities.map { entity ->
            AddOnPriceLineDto(
                key = entity.key.name,
                name = entity.name,
                monthlyPriceGrossCents = entity.monthlyPriceGrossCents
            )
        }

        val hasUndefinedPrices = addOnLines.any { it.monthlyPriceGrossCents == null }
        val totalCents = if (hasUndefinedPrices) null
        else basePlan.monthlyPriceGrossCents + addOnLines.sumOf { it.monthlyPriceGrossCents!! }

        val fullPlan = planRepository.findByKey(PlanKey.FULL)
        val savings = if (totalCents != null && fullPlan != null && totalCents > fullPlan.monthlyPriceGrossCents)
            totalCents - fullPlan.monthlyPriceGrossCents
        else null

        return CustomPriceResponse(
            basePlanKey = basePlan.key.name,
            basePlanName = basePlan.name,
            basePlanMonthlyPriceCents = basePlan.monthlyPriceGrossCents,
            addOns = addOnLines,
            totalMonthlyPriceCents = totalCents,
            hasUndefinedPrices = hasUndefinedPrices,
            fullPlanMonthlyPriceCents = fullPlan?.monthlyPriceGrossCents,
            savingsWithFullCents = savings
        )
    }
}
