package pl.detailing.crm.subscription.pricing

import org.springframework.stereotype.Service
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.lifecycle.SubscriptionAccessPolicy
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Jak wolno dziś kupić coś „w trakcie okresu" (upgrade planu, dokupienie modułu).
 *
 * Wcześniej o tym decydowała proracja zwracająca null — a null znaczył naraz „trial, za
 * darmo" i „okres już minął". Studio z wygasłą subskrypcją dostawało więc upgrade do FULL
 * za 0 zł (audyt, S4).
 */
enum class MidPeriodPurchaseMode {
    /** Trwa trial: zmiana bezpłatna, pełna cena od pierwszego zakupu okresu. */
    TRIAL_FREE,
    /** Trwa opłacony okres: dopłata proporcjonalna do pozostałych dni. */
    PRORATED,
    /** Brak trwającego okresu (karencja, wygasłe, bez planu) — najpierw odnowienie lub zakup. */
    NOT_ALLOWED
}

/** Moduł opłacony w bieżącym okresie — do zaliczenia przy upgradzie na plan, który go zawiera. */
data class PaidAddOnCredit(val monthlyPriceCents: Long, val cancelAt: Instant?)

/**
 * Calculates prorated billing amounts for mid-period plan changes and add-on activations.
 *
 * Strategy (Stripe-style daily rate):
 *   daysRemaining   = floor(subscriptionEndsAt - now), at least 1
 *   proratedAmount  = round(monthlyPriceCents × daysRemaining / 30)
 *
 * Zaokrąglenie dopiero na końcu: dawniej stawka dzienna była zaokrąglana przed mnożeniem,
 * więc (29900 − 9900)/30 = 666,67 → 667 gr, a za 30 dni 20010 gr zamiast 20000 (audyt, S9).
 *
 * Downgrades are never charged mid-period — the new (lower) plan takes effect at
 * the end of the paid period, so there is nothing to bill.
 */
@Service
class ProrationService(
    private val studioRepository: StudioRepository,
    private val accessPolicy: SubscriptionAccessPolicy
) {

    /**
     * Describes the proration for a single billing action.
     * [effectiveAt] is when the change will actually take effect — upgrades/add-ons: immediately.
     */
    data class ProrationResult(
        val daysRemaining: Long,
        val periodEndsAt: Instant,
        val proratedAmountCents: Long,
        val currency: String
    )

    fun midPeriodPurchaseMode(studioId: StudioId): MidPeriodPurchaseMode {
        val billing = studioRepository.findByStudioId(studioId.value)?.billing() ?: return MidPeriodPurchaseMode.NOT_ALLOWED
        val now = accessPolicy.now()
        return when {
            accessPolicy.isTrialRunning(billing, now) -> MidPeriodPurchaseMode.TRIAL_FREE
            accessPolicy.hasRunningPaidPeriod(billing, now) -> MidPeriodPurchaseMode.PRORATED
            else -> MidPeriodPurchaseMode.NOT_ALLOWED
        }
    }

    /**
     * Prorated charge for activating an add-on mid-period.
     * Null when no paid period is running (trial → free; otherwise the caller rejects the purchase).
     */
    fun calculateAddOnActivation(studioId: StudioId, monthlyPriceCents: Long): ProrationResult? {
        val endsAt = runningPeriodEnd(studioId) ?: return null
        val days = daysUntil(endsAt)
        return ProrationResult(days, endsAt, prorate(monthlyPriceCents, days), CURRENCY)
    }

    /**
     * Prorated charge for upgrading to a more expensive plan, z zaliczeniem modułów
     * opłaconych w tym okresie: plan docelowy je zawiera, a upgrade je kasuje — dawniej
     * klient płacił za nie drugi raz (audyt, S7). Moduł z zaplanowanym wyłączeniem liczy się
     * tylko do dnia wyłączenia. Wynik nigdy nie jest ujemny (BASIC + wszystkie moduły > FULL).
     * Null when no paid period is running.
     */
    fun calculatePlanUpgrade(
        studioId: StudioId,
        currentMonthlyPriceCents: Long,
        newMonthlyPriceCents: Long,
        paidAddOns: List<PaidAddOnCredit> = emptyList()
    ): ProrationResult? {
        if (newMonthlyPriceCents <= currentMonthlyPriceCents) return null
        val endsAt = runningPeriodEnd(studioId) ?: return null
        val days = daysUntil(endsAt)

        val planDifference = BigDecimal(newMonthlyPriceCents - currentMonthlyPriceCents).multiply(BigDecimal(days))
        val addOnCredit = paidAddOns.fold(BigDecimal.ZERO) { acc, addOn ->
            val creditEnd = addOn.cancelAt?.takeIf { it.isBefore(endsAt) } ?: endsAt
            val creditDays = if (creditEnd.isAfter(accessPolicy.now())) daysUntil(creditEnd) else 0L
            acc + BigDecimal(addOn.monthlyPriceCents).multiply(BigDecimal(creditDays))
        }
        val amount = (planDifference - addOnCredit).divide(DAYS_IN_PERIOD, 0, RoundingMode.HALF_UP).toLong()
        return ProrationResult(days, endsAt, amount.coerceAtLeast(0), CURRENCY)
    }

    /** Koniec trwającego opłaconego okresu; null w trialu, karencji i po wygaśnięciu. */
    fun runningPeriodEnd(studioId: StudioId): Instant? {
        val billing = studioRepository.findByStudioId(studioId.value)?.billing() ?: return null
        return if (accessPolicy.hasRunningPaidPeriod(billing)) billing.subscriptionEndsAt else null
    }

    /** Returns how many days are left in the running paid period, or null. */
    fun daysRemainingInPeriod(studioId: StudioId): Long? = runningPeriodEnd(studioId)?.let(::daysUntil)

    private fun daysUntil(endsAt: Instant): Long =
        ChronoUnit.DAYS.between(accessPolicy.now(), endsAt).coerceAtLeast(1)

    companion object {
        private const val CURRENCY = "PLN"
        private val DAYS_IN_PERIOD = BigDecimal(30)

        fun prorate(monthlyCents: Long, days: Long): Long =
            BigDecimal(monthlyCents).multiply(BigDecimal(days)).divide(DAYS_IN_PERIOD, 0, RoundingMode.HALF_UP).toLong()
    }
}
