package pl.detailing.crm.subscription.lifecycle

import pl.detailing.crm.shared.SubscriptionStatus
import java.time.Duration
import java.time.Instant

/**
 * Stan rozliczeniowy studia — to, co trzeba wiedzieć, żeby odpowiedzieć „czy studio może
 * teraz korzystać z produktu" i „jakie przejście statusu jest należne".
 *
 * Trafia do cache'u razem z planem ([pl.detailing.crm.subscription.entitlement.domain.StudioEntitlements]),
 * więc zawiera DATY, nie gotową decyzję: decyzja zależy od chwili sprawdzenia, a wpis
 * w cache'u żyje kilka minut.
 */
data class BillingSnapshot(
    val status: SubscriptionStatus,
    val trialEndsAt: Instant?,
    val subscriptionEndsAt: Instant?,
    val graceEndsAt: Instant?
)

/** Przejście statusu należne w danej chwili — wynik [SubscriptionLifecycle.dueTransition]. */
data class LifecycleTransition(
    val to: SubscriptionStatus,
    /** Ustawiane przy wejściu w PAST_DUE; null w pozostałych przejściach (czyści stary termin). */
    val graceEndsAt: Instant?
)

/**
 * JEDYNA definicja cyklu życia subskrypcji. Czysta funkcja: bez Springa, bez bazy, bez zegara.
 *
 * Wcześniej ta wiedza była rozsiana: `Studio.isAccessible()` liczył dostęp z dat,
 * `StudioRepository.findExpired*` miały własne granice (`<` zamiast `isAfter`), scheduler
 * przestawiał status niezależnie od jednego i drugiego, a `PAST_DUE` dawał dostęp bez
 * żadnej daty. Interceptor HTTP, zadania w tle i job wygaszający pytają teraz tutaj — więc
 * w tej samej chwili dają tę samą odpowiedź.
 *
 * ```
 * NO_PLAN ──trial──▶ TRIALING ──trial_ends_at──▶ EXPIRED
 *                                                  ▲
 * ACTIVE ──subscription_ends_at──▶ PAST_DUE ──grace_ends_at──┘   (grace = 0 → od razu EXPIRED)
 *   ▲                                 │
 *   └──────── opłacone odnowienie ────┘
 * ```
 *
 * Granica czasu jest wszędzie ta sama: termin jest „w przyszłości", gdy `isAfter(now)`.
 * Studio ACTIVE, którego okres właśnie minął, a job jeszcze go nie przestawił, jest już
 * traktowane jak w okresie karencji — opóźnienie joba nie zmienia odpowiedzi.
 */
object SubscriptionLifecycle {

    /** Czy studio może korzystać z produktu (API biznesowe i funkcje działające w tle). */
    fun isAccessible(billing: BillingSnapshot, now: Instant, grace: Duration): Boolean =
        accessEndsAt(billing, grace)?.isAfter(now) == true

    /**
     * Do kiedy trwa dostęp, jeśli nic się nie zmieni (nikt nie zapłaci).
     * Null — dostępu nie ma i nie będzie bez zakupu (NO_PLAN, EXPIRED, brak dat).
     */
    fun accessEndsAt(billing: BillingSnapshot, grace: Duration): Instant? = when (billing.status) {
        SubscriptionStatus.NO_PLAN -> null
        SubscriptionStatus.TRIALING -> billing.trialEndsAt
        SubscriptionStatus.ACTIVE -> billing.subscriptionEndsAt?.plus(grace)
        SubscriptionStatus.PAST_DUE -> graceEndOf(billing, grace)
        SubscriptionStatus.EXPIRED -> null
    }

    /** Okres karencji: opłacony okres minął, dostęp jeszcze trwa, czeka na odnowienie. */
    fun isInGrace(billing: BillingSnapshot, now: Instant, grace: Duration): Boolean = when (billing.status) {
        SubscriptionStatus.PAST_DUE -> isAccessible(billing, now, grace)
        SubscriptionStatus.ACTIVE -> billing.subscriptionEndsAt?.isAfter(now) == false &&
                isAccessible(billing, now, grace)
        else -> false
    }

    /** Trwa opłacony okres (nie karencja, nie trial) — tylko wtedy proracja ma sens. */
    fun hasRunningPaidPeriod(billing: BillingSnapshot, now: Instant): Boolean =
        billing.status == SubscriptionStatus.ACTIVE && billing.subscriptionEndsAt?.isAfter(now) == true

    fun isTrialRunning(billing: BillingSnapshot, now: Instant): Boolean =
        billing.status == SubscriptionStatus.TRIALING && billing.trialEndsAt?.isAfter(now) == true

    /**
     * Przejście wynikające z upływu czasu, należne w chwili [now]; null, gdy nic nie jest należne.
     *
     * Wołane przez job pod blokadą wiersza studia — decyzja zapada na stanie spod blokady,
     * więc dwie instancje joba nie wykonają tego samego przejścia dwa razy.
     */
    fun dueTransition(billing: BillingSnapshot, now: Instant, grace: Duration): LifecycleTransition? =
        when (billing.status) {
            SubscriptionStatus.TRIALING ->
                if (billing.trialEndsAt?.isAfter(now) == false) LifecycleTransition(SubscriptionStatus.EXPIRED, null)
                else null

            SubscriptionStatus.ACTIVE -> {
                val endsAt = billing.subscriptionEndsAt
                when {
                    endsAt == null || endsAt.isAfter(now) -> null
                    endsAt.plus(grace).isAfter(now) ->
                        LifecycleTransition(SubscriptionStatus.PAST_DUE, endsAt.plus(grace))
                    else -> LifecycleTransition(SubscriptionStatus.EXPIRED, null)
                }
            }

            // PAST_DUE bez żadnej daty to stan uszkodzony (dawniej: dostęp bezterminowy) —
            // zamykamy go, zamiast zostawiać otwarty.
            SubscriptionStatus.PAST_DUE ->
                if (graceEndOf(billing, grace)?.isAfter(now) == true) null
                else LifecycleTransition(SubscriptionStatus.EXPIRED, null)

            SubscriptionStatus.NO_PLAN, SubscriptionStatus.EXPIRED -> null
        }

    /**
     * Od kiedy liczy się okres kupiony płatnością otrzymaną w chwili [paidAt].
     *
     *  - trwający okres albo karencja → od końca opłaconego okresu: dni karencji są płatne,
     *    inaczej każde spóźnione odnowienie dawałoby darmowe dni;
     *  - trial → od końca triala: zakup w trakcie triala nie przepala jego reszty;
     *  - brak dostępu (EXPIRED, NO_PLAN, karencja już minęła) → od chwili zapłaty.
     *
     * Liczone od [paidAt] (chwili otrzymania pieniędzy), nie od chwili realizacji — ponowiona
     * realizacja nie przesuwa dat.
     */
    fun paidPeriodStart(billing: BillingSnapshot, paidAt: Instant, grace: Duration): Instant = when {
        billing.status == SubscriptionStatus.TRIALING && billing.trialEndsAt?.isAfter(paidAt) == true ->
            billing.trialEndsAt
        (billing.status == SubscriptionStatus.ACTIVE || billing.status == SubscriptionStatus.PAST_DUE) &&
                billing.subscriptionEndsAt != null && isAccessible(billing, paidAt, grace) ->
            billing.subscriptionEndsAt
        else -> paidAt
    }

    private fun graceEndOf(billing: BillingSnapshot, grace: Duration): Instant? =
        billing.graceEndsAt ?: billing.subscriptionEndsAt?.plus(grace)
}
