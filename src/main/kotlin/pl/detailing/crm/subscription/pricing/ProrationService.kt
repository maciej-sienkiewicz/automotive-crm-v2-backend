package pl.detailing.crm.subscription.pricing

import org.springframework.stereotype.Service
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentJpaRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Calculates prorated billing amounts for mid-period plan changes and add-on activations.
 *
 * Strategy (Stripe-style daily rate):
 *   dailyRate       = monthlyPriceCents / 30
 *   daysRemaining   = ceil(subscriptionEndsAt - now)
 *   proratedAmount  = dailyRate × daysRemaining
 *
 * This formula is independent of whether the studio bought a monthly or annual plan,
 * because add-on prices are always expressed as monthly rates and we convert to daily.
 *
 * For plan upgrades the same formula applies to the price *difference*:
 *   proratedUpgradeAmount = dailyRate(newPlan - currentPlan) × daysRemaining
 *
 * Downgrades are never charged mid-period — the new (lower) plan takes effect at
 * [periodEndsAt], so there is nothing to bill.
 */
@Service
class ProrationService(
    private val studioRepository: StudioRepository,
    private val paymentRepository: SubscriptionPaymentJpaRepository
) {

    /**
     * Describes the proration for a single billing action.
     *
     * [proratedAmountCents] is null when no charge applies (downgrade or trial studio).
     * [effectiveAt] is when the change will actually take effect:
     *   - upgrades/add-ons: immediately (Instant.now())
     *   - downgrades: at period end ([periodEndsAt])
     */
    data class ProrationResult(
        val daysRemaining: Long,
        val periodEndsAt: Instant?,
        val dailyRateCents: Long,
        val proratedAmountCents: Long,
        val currency: String
    )

    /**
     * Calculates the prorated charge for activating an add-on mid-period.
     * Returns null if the studio is on a trial (no subscriptionEndsAt).
     */
    fun calculateAddOnActivation(studioId: StudioId, monthlyPriceCents: Long): ProrationResult? {
        val studio = studioRepository.findByStudioId(studioId.value) ?: return null
        val endsAt = studio.subscriptionEndsAt ?: return null

        val now = Instant.now()
        if (endsAt.isBefore(now)) return null

        val daysRemaining = ChronoUnit.DAYS.between(now, endsAt).coerceAtLeast(1)
        val dailyRateCents = BigDecimal(monthlyPriceCents)
            .divide(BigDecimal(30), 0, RoundingMode.HALF_UP)
            .toLong()

        return ProrationResult(
            daysRemaining = daysRemaining,
            periodEndsAt = endsAt,
            dailyRateCents = dailyRateCents,
            proratedAmountCents = dailyRateCents * daysRemaining,
            currency = "PLN"
        )
    }

    /**
     * Calculates the prorated charge for upgrading to a more expensive plan.
     * [currentMonthlyPriceCents] is the price of the studio's current plan.
     * [newMonthlyPriceCents] must be greater (upgrade) — returns null for downgrades.
     */
    fun calculatePlanUpgrade(
        studioId: StudioId,
        currentMonthlyPriceCents: Long,
        newMonthlyPriceCents: Long
    ): ProrationResult? {
        if (newMonthlyPriceCents <= currentMonthlyPriceCents) return null

        val studio = studioRepository.findByStudioId(studioId.value) ?: return null
        val endsAt = studio.subscriptionEndsAt ?: return null

        val now = Instant.now()
        if (endsAt.isBefore(now)) return null

        val daysRemaining = ChronoUnit.DAYS.between(now, endsAt).coerceAtLeast(1)
        val diffMonthlyRateCents = newMonthlyPriceCents - currentMonthlyPriceCents
        val dailyRateCents = BigDecimal(diffMonthlyRateCents)
            .divide(BigDecimal(30), 0, RoundingMode.HALF_UP)
            .toLong()

        return ProrationResult(
            daysRemaining = daysRemaining,
            periodEndsAt = endsAt,
            dailyRateCents = dailyRateCents,
            proratedAmountCents = dailyRateCents * daysRemaining,
            currency = "PLN"
        )
    }

    /** Returns how many days are left in the active billing period, or null if trial. */
    fun daysRemainingInPeriod(studioId: StudioId): Long? {
        val studio = studioRepository.findByStudioId(studioId.value) ?: return null
        val endsAt = studio.subscriptionEndsAt ?: return null
        val remaining = ChronoUnit.DAYS.between(Instant.now(), endsAt)
        return remaining.coerceAtLeast(0)
    }
}
