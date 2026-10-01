package pl.detailing.crm.subscription.pricing

import org.springframework.stereotype.Service
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.lifecycle.SubscriptionAccessPolicy
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

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
 *   okno           = [max(teraz, koniec triala), subscriptionEndsAt]  — opłacony czas, który zostaje
 *   proratedAmount = round(monthlyPriceCents × max(długość okna, 1 doba) / 30 dni)
 *
 * Proporcja liczona co do sekundy, zaokrąglenie dopiero na końcu. Dawniej stawka dzienna była
 * zaokrąglana przed mnożeniem ((29900 − 9900)/30 = 666,67 → 667 gr, za 30 dni 20010 gr zamiast
 * 20000 — audyt, S9), a potem liczba dni była zaokrąglana w dół: zakup na 29 dni i 23 h
 * kosztował jak na 29 dni, choć działał 30 (przegląd planu naprawczego).
 *
 * Okno zaczyna się po trialu, jeśli pakiet kupiono w jego trakcie: reszta triala jest darmowa
 * i nie jest częścią opłaconego okresu ([pl.detailing.crm.subscription.lifecycle.SubscriptionLifecycle.billableFrom]).
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
     * [daysRemaining] — do opisu dla człowieka: rozpoczęte dni okna (zaokrąglone w górę).
     */
    data class ProrationResult(
        val daysRemaining: Long,
        val periodEndsAt: Instant,
        val proratedAmountCents: Long,
        val currency: String
    )

    /**
     * Opłacony czas, który zostaje do końca trwającego okresu. [chargeable] — co najmniej doba:
     * zakup na sekundy przed końcem okresu kosztowałby 0 zł, a upgrade zostaje także w karencji.
     */
    private data class BillableWindow(val from: Instant, val endsAt: Instant) {
        val length: Duration get() = Duration.between(from, endsAt)
        val chargeable: Duration get() = maxOf(length, MINIMUM_CHARGE)
    }

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
        val window = billableWindow(studioId) ?: return null
        return ProrationResult(daysLabel(window.length), window.endsAt, prorate(monthlyPriceCents, window.chargeable), CURRENCY)
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
        val window = billableWindow(studioId) ?: return null

        val planDifference = BigDecimal(newMonthlyPriceCents - currentMonthlyPriceCents).multiply(BigDecimal(window.chargeable.seconds))
        val addOnCredit = paidAddOns.fold(BigDecimal.ZERO) { acc, addOn ->
            val creditEnd = addOn.cancelAt?.takeIf { it.isBefore(window.endsAt) } ?: window.endsAt
            val creditSeconds = if (creditEnd.isAfter(window.from)) Duration.between(window.from, creditEnd).seconds else 0L
            acc + BigDecimal(addOn.monthlyPriceCents).multiply(BigDecimal(creditSeconds))
        }
        val amount = (planDifference - addOnCredit).divide(SECONDS_IN_PERIOD, 0, RoundingMode.HALF_UP).toLong()
        return ProrationResult(daysLabel(window.length), window.endsAt, amount.coerceAtLeast(0), CURRENCY)
    }

    /** Koniec trwającego opłaconego okresu; null w trialu, karencji i po wygaśnięciu. */
    fun runningPeriodEnd(studioId: StudioId): Instant? {
        val billing = studioRepository.findByStudioId(studioId.value)?.billing() ?: return null
        return if (accessPolicy.hasRunningPaidPeriod(billing)) billing.subscriptionEndsAt else null
    }

    /** Rozpoczęte dni opłaconego czasu do końca trwającego okresu, or null. */
    fun daysRemainingInPeriod(studioId: StudioId): Long? = billableWindow(studioId)?.let { daysLabel(it.length) }

    private fun billableWindow(studioId: StudioId): BillableWindow? {
        val billing = studioRepository.findByStudioId(studioId.value)?.billing() ?: return null
        val now = accessPolicy.now()
        if (!accessPolicy.hasRunningPaidPeriod(billing, now)) return null
        val endsAt = billing.subscriptionEndsAt!!
        return BillableWindow(accessPolicy.billableFrom(billing, now).coerceAtMost(endsAt), endsAt)
    }

    private fun Instant.coerceAtMost(limit: Instant): Instant = if (isAfter(limit)) limit else this

    companion object {
        private const val CURRENCY = "PLN"
        private val PERIOD: Duration = Duration.ofDays(30)
        private val MINIMUM_CHARGE: Duration = Duration.ofDays(1)
        private val SECONDS_IN_PERIOD = BigDecimal(PERIOD.seconds)

        /** Cena za [length] opłaconego czasu przy cenie [monthlyCents] za 30 dni, zaokrąglona raz, na końcu. */
        fun prorate(monthlyCents: Long, length: Duration): Long =
            BigDecimal(monthlyCents).multiply(BigDecimal(length.seconds.coerceAtLeast(0)))
                .divide(SECONDS_IN_PERIOD, 0, RoundingMode.HALF_UP).toLong()

        fun prorate(monthlyCents: Long, days: Long): Long = prorate(monthlyCents, Duration.ofDays(days))

        /** Rozpoczęte dni — „za 29 dni i 23 h" to dla człowieka 30 dni, nie 29. Co najmniej 1. */
        fun daysLabel(length: Duration): Long {
            val days = length.toDays()
            return (if (length.minusDays(days).isZero) days else days + 1).coerceAtLeast(1)
        }
    }
}
