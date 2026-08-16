package pl.detailing.crm.subscription.management

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.detailing.crm.subscription.SubscriptionService

/**
 * Moves billing statuses along the lifecycle:
 *   TRIALING → EXPIRED (trial_ends_at passed), ACTIVE → EXPIRED (subscription_ends_at passed).
 *
 * Before this scheduler existed, [SubscriptionService.expireTrials] and
 * [SubscriptionService.expireSubscriptions] were dead code — statuses in the DB
 * never left TRIALING/ACTIVE and access control worked only because
 * Studio.isAccessible() re-computes dates on every read. Persisted statuses now
 * match reality, so reporting, reconciliation and PAST_DUE handling can rely on them.
 *
 * Multi-instance safety: both sweeps are idempotent status flips guarded by a
 * date predicate — two instances racing produce the same end state (same caveat
 * as [PlanDowngradeScheduler]).
 */
@Component
class SubscriptionLifecycleScheduler(
    private val subscriptionService: SubscriptionService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Hourly at :10 — offset from the downgrade scheduler (:00) to spread DB load. */
    @Scheduled(cron = "0 10 * * * *")
    fun expireLapsedSubscriptions() = runBlocking {
        val expiredTrials = subscriptionService.expireTrials()
        val expiredSubscriptions = subscriptionService.expireSubscriptions()
        if (expiredTrials > 0 || expiredSubscriptions > 0) {
            logger.info(
                "Subscription lifecycle sweep: {} trial(s) and {} subscription(s) marked EXPIRED",
                expiredTrials, expiredSubscriptions
            )
        }
    }
}
