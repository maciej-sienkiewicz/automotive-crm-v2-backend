package pl.detailing.crm.subscription.lifecycle

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * [gracePeriodDays] — ile dni po końcu opłaconego okresu studio działa dalej (PAST_DUE),
 *   czekając na odnowienie. Odnowienie w karencji liczy nowy okres od starego końca, więc
 *   karencja nie jest darmowa dla płacących; darmowa jest tylko dla tych, którzy nie zapłacą.
 *   0 = bez karencji (dostęp kończy się co do sekundy `subscription_ends_at`).
 */
@ConfigurationProperties(prefix = "subscription")
data class SubscriptionProperties(
    val gracePeriodDays: Long = 7
) {
    val gracePeriod: Duration get() = Duration.ofDays(gracePeriodDays.coerceAtLeast(0))
}

@Configuration
@EnableConfigurationProperties(SubscriptionProperties::class)
class SubscriptionConfig

/**
 * [SubscriptionLifecycle] z wpiętą konfiguracją i zegarem — to woła interceptor HTTP,
 * [pl.detailing.crm.subscription.entitlement.capability.CapabilityService] i joby.
 * Jedna instancja odpowiedzi na „czy to studio może teraz działać" dla całej aplikacji.
 */
@Component
class SubscriptionAccessPolicy(
    private val properties: SubscriptionProperties,
    /** Podmieniany w testach; produkcyjnie zegar systemowy (Spring bierze wartość domyślną). */
    private val clock: Clock = Clock.systemUTC()
) {
    val gracePeriod: Duration get() = properties.gracePeriod

    fun now(): Instant = clock.instant()

    fun isAccessible(billing: BillingSnapshot, at: Instant = now()): Boolean =
        SubscriptionLifecycle.isAccessible(billing, at, gracePeriod)

    fun isInGrace(billing: BillingSnapshot, at: Instant = now()): Boolean =
        SubscriptionLifecycle.isInGrace(billing, at, gracePeriod)

    fun accessEndsAt(billing: BillingSnapshot): Instant? =
        SubscriptionLifecycle.accessEndsAt(billing, gracePeriod)

    fun dueTransition(billing: BillingSnapshot, at: Instant = now()): LifecycleTransition? =
        SubscriptionLifecycle.dueTransition(billing, at, gracePeriod)

    fun paidPeriodStart(billing: BillingSnapshot, paidAt: Instant): Instant =
        SubscriptionLifecycle.paidPeriodStart(billing, paidAt, gracePeriod)

    fun hasRunningPaidPeriod(billing: BillingSnapshot, at: Instant = now()): Boolean =
        SubscriptionLifecycle.hasRunningPaidPeriod(billing, at)

    fun billableFrom(billing: BillingSnapshot, at: Instant = now()): Instant =
        SubscriptionLifecycle.billableFrom(billing, at)

    fun isTrialRunning(billing: BillingSnapshot, at: Instant = now()): Boolean =
        SubscriptionLifecycle.isTrialRunning(billing, at)
}
