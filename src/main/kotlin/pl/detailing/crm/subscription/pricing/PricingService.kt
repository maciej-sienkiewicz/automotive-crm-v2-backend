package pl.detailing.crm.subscription.pricing

import org.springframework.stereotype.Service
import pl.detailing.crm.shared.EntityNotFoundException
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
    private val addOnRepository: AddOnJpaRepository
) {
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
