package pl.detailing.crm.subscription.management

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Detects drift between the money side and the entitlement side of the
 * subscription system. It never repairs silently — it makes drift IMPOSSIBLE
 * TO MISS, because every incident this job reports is either a paying customer
 * who did not get what they paid for, or a studio using paid features for free.
 *
 * Checks, each exported as a gauge (wire alerts to non-zero values):
 *
 *  1. `subscription.reconciliation.studios.missing.plan.row`
 *     Studios with billing status TRIALING/ACTIVE but no `studio_subscription_plans`
 *     row — a violation of the provisioning invariant (should be 0 after
 *     StudioSubscriptionBackfill; non-zero means provisioning regressed).
 *
 *  2. `subscription.reconciliation.orders.stuck.pending`
 *     Payment orders PENDING for over an hour with a P24 token issued — the buyer
 *     may have paid while the webhook keeps failing (this was the exact signature
 *     of the "Studio nie ma aktywnego planu subskrypcji" incident: money captured,
 *     fulfillment rolling back on every retry).
 *
 *  3. `subscription.reconciliation.orders.paid.unlogged`
 *     Orders PAID with no matching audit entry in `subscription_payment_log` —
 *     fulfillment claims success but left no trace; investigate immediately.
 */
@Component
class SubscriptionReconciliationJob(
    private val jdbcTemplate: JdbcTemplate,
    meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val studiosMissingPlanRow = meterRegistry.gauge(
        "subscription.reconciliation.studios.missing.plan.row", AtomicLong(0)
    )!!
    private val ordersStuckPending = meterRegistry.gauge(
        "subscription.reconciliation.orders.stuck.pending", AtomicLong(0)
    )!!
    private val ordersPaidUnlogged = meterRegistry.gauge(
        "subscription.reconciliation.orders.paid.unlogged", AtomicLong(0)
    )!!

    /** Every 15 minutes; read-only queries against indexed columns. */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT2M")
    fun reconcile() {
        reportStudiosMissingPlanRow()
        reportStuckPendingOrders()
        reportPaidOrdersWithoutAuditLog()
    }

    private fun reportStudiosMissingPlanRow() {
        val ids = jdbcTemplate.query(
            """
            SELECT s.id FROM studios s
            WHERE s.subscription_status IN ('TRIALING', 'ACTIVE')
              AND NOT EXISTS (SELECT 1 FROM studio_subscription_plans sp WHERE sp.studio_id = s.id)
            """.trimIndent()
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }

        studiosMissingPlanRow.set(ids.size.toLong())
        if (ids.isNotEmpty()) {
            logger.error("RECONCILIATION: {} active/trialing studio(s) without a plan row: {}", ids.size, ids)
        }
    }

    private fun reportStuckPendingOrders() {
        val rows = jdbcTemplate.query(
            """
            SELECT id, studio_id FROM payment_orders
            WHERE status = 'PENDING'
              AND p24_token IS NOT NULL
              AND created_at < now() - interval '1 hour'
            """.trimIndent()
        ) { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getObject("studio_id", UUID::class.java) }

        ordersStuckPending.set(rows.size.toLong())
        if (rows.isNotEmpty()) {
            logger.error(
                "RECONCILIATION: {} payment order(s) stuck PENDING >1h with a P24 token — " +
                "possible captured payments without fulfillment: {}", rows.size, rows
            )
        }
    }

    private fun reportPaidOrdersWithoutAuditLog() {
        val ids = jdbcTemplate.query(
            """
            SELECT o.id FROM payment_orders o
            WHERE o.status = 'PAID'
              AND o.paid_at < now() - interval '15 minutes'
              AND NOT EXISTS (
                  SELECT 1 FROM subscription_payment_log l
                  WHERE l.transaction_id = CAST(o.p24_order_id AS TEXT)
                     OR l.transaction_id = o.session_id
              )
            """.trimIndent()
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }

        ordersPaidUnlogged.set(ids.size.toLong())
        if (ids.isNotEmpty()) {
            logger.error("RECONCILIATION: {} PAID order(s) without a payment-log entry: {}", ids.size, ids)
        }
    }
}
