package pl.detailing.crm.subscription.management

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.infrastructure.SubscriptionEventType
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentLogEntity
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentLogRepository
import java.time.Instant

/**
 * Scheduled job that applies deferred plan downgrades.
 *
 * Background:
 *   When a studio requests a downgrade (e.g. FULL → BASIC), the change is not
 *   applied immediately — the studio keeps its current plan until the end of its paid
 *   billing period. [PlanManagementService.changePlan] records the intent as a
 *   [PendingPlanChangeEntity] with [PendingPlanChangeStatus.PENDING].
 *
 * This job runs every hour and processes all PENDING rows whose [effectiveAt]
 * timestamp has passed:
 *   1. Calls [EntitlementService.assignPlan] to switch the plan and evict the cache.
 *   2. Marks the row as [PendingPlanChangeStatus.APPLIED].
 *   3. Appends a [SubscriptionPaymentLogEntity] entry for the audit trail.
 *
 * Failure handling:
 *   Each row is processed independently inside its own try/catch so a failure
 *   on one studio does not block others. Failed rows remain PENDING and will be
 *   retried on the next run. After repeated failures an operator can inspect the
 *   row via the DB and the ERROR log entry.
 *
 * Multi-instance safety:
 *   The job may run on multiple instances simultaneously in a scaled deployment.
 *   The [PendingPlanChangeRepository.findDueChanges] query does not use SELECT FOR
 *   UPDATE (not all JPA providers support it uniformly), so a small window exists
 *   where two instances process the same row. The worst outcome is that
 *   [EntitlementService.assignPlan] is called twice with the same plan key — which
 *   is idempotent. For strict deduplication, replace with a database-level advisory
 *   lock or a distributed lock (Redisson) before going to production at scale.
 */
@Component
class PlanDowngradeScheduler(
    private val pendingPlanChangeRepository: PendingPlanChangeRepository,
    private val entitlementService: EntitlementService,
    private val paymentLogRepository: SubscriptionPaymentLogRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Runs every hour at the top of the hour.
     * Processes all PENDING downgrade rows whose effective date has arrived.
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    fun applyDueDowngrades() {
        val now = Instant.now()
        val due = pendingPlanChangeRepository.findDueChanges(now)

        if (due.isEmpty()) return

        logger.info("PlanDowngradeScheduler: found {} pending downgrade(s) to apply", due.size)

        var applied = 0
        var failed = 0

        for (pending in due) {
            try {
                applyDowngrade(pending, now)
                applied++
            } catch (e: Exception) {
                failed++
                logger.error(
                    "Failed to apply downgrade id={} studio={} from={} to={}: {}",
                    pending.id, pending.studioId, pending.fromPlanKey, pending.toPlanKey, e.message, e
                )
            }
        }

        logger.info(
            "PlanDowngradeScheduler: applied={} failed={}",
            applied, if (failed > 0) "$failed (see ERROR logs above)" else 0
        )
    }

    private fun applyDowngrade(pending: PendingPlanChangeEntity, now: Instant) {
        val studioId = StudioId(pending.studioId)

        entitlementService.assignPlan(studioId, pending.toPlanKey)

        pending.status = PendingPlanChangeStatus.APPLIED
        pending.appliedAt = now
        pendingPlanChangeRepository.save(pending)

        paymentLogRepository.save(
            SubscriptionPaymentLogEntity(
                studioId = pending.studioId,
                eventType = SubscriptionEventType.PLAN_DOWNGRADE,
                amountInCents = 0,
                planKey = pending.toPlanKey,
                description = "Downgrade z ${pending.fromPlanKey.displayName} do ${pending.toPlanKey.displayName} — zastosowany automatycznie"
            )
        )

        logger.info(
            "Applied downgrade studio={} from={} to={} (scheduled for {}, applied at {})",
            studioId, pending.fromPlanKey, pending.toPlanKey, pending.effectiveAt, now
        )
    }
}
