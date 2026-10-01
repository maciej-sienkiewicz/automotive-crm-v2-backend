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
 * (Naprawia — to, co da się naprawić automatycznie — `PaymentReconciliationJob`.)
 *
 * Checks, each exported as a gauge (wire alerts to non-zero values):
 *
 *  1. `subscription.reconciliation.studios.missing.plan.row`
 *     Studios with billing status TRIALING/ACTIVE/PAST_DUE but no `studio_subscription_plans`
 *     row — a violation of the provisioning invariant.
 *
 *  2. `subscription.reconciliation.orders.stuck.pending`
 *     Zamówienia PENDING z tokenem P24 starsze niż czas ważności + 30 min. Worker
 *     rekoncyliacji wygasza porzucone koszyki, więc tu zostaje tylko to, czego nie umiał
 *     domknąć (P24 niedostępne, błąd w kodzie). Dawniej miernik liczył każdy porzucony
 *     koszyk i dzwonił zawsze (audyt, P5).
 *
 *  3. `subscription.reconciliation.orders.paid.unfulfilled`
 *     Pieniądze są (PAID) od ponad 15 minut, efektu nie ma — realizacja się nie udaje.
 *
 *  4. `subscription.reconciliation.orders.paid.unlogged`
 *     Orders FULFILLED with no matching entry in `subscription_payment_log`.
 *
 *  5. `subscription.reconciliation.orders.refund.required`
 *     Zapłacone, a niemożliwe do zrealizowania — każde wymaga zwrotu przez operatora.
 *
 *  6. `subscription.reconciliation.notifications.needs.review`
 *     Notyfikacje płatności, których system nie rozstrzygnie sam (druga płatność za
 *     opłacone zamówienie, niezgodna kwota, wyczerpane ponowienia, brak zamówienia po 24 h).
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
    private val ordersPaidUnfulfilled = meterRegistry.gauge(
        "subscription.reconciliation.orders.paid.unfulfilled", AtomicLong(0)
    )!!
    private val ordersPaidUnlogged = meterRegistry.gauge(
        "subscription.reconciliation.orders.paid.unlogged", AtomicLong(0)
    )!!
    private val ordersRefundRequired = meterRegistry.gauge(
        "subscription.reconciliation.orders.refund.required", AtomicLong(0)
    )!!
    private val notificationsNeedsReview = meterRegistry.gauge(
        "subscription.reconciliation.notifications.needs.review", AtomicLong(0)
    )!!

    /** Every 15 minutes; read-only queries against indexed columns. */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT2M")
    fun reconcile() {
        report(studiosMissingPlanRow, "active/trialing studio(s) without a plan row", """
            SELECT s.id FROM studios s
            WHERE s.subscription_status IN ('TRIALING', 'ACTIVE', 'PAST_DUE')
              AND NOT EXISTS (SELECT 1 FROM studio_subscription_plans sp WHERE sp.studio_id = s.id)
        """)
        report(ordersStuckPending, "payment order(s) stuck PENDING with a P24 token past expiry", """
            SELECT id FROM payment_orders
            WHERE status = 'PENDING'
              AND p24_token IS NOT NULL
              AND created_at < now() - interval '90 minutes'
        """)
        report(ordersPaidUnfulfilled, "PAID order(s) not fulfilled for over 15 minutes", """
            SELECT id FROM payment_orders
            WHERE status = 'PAID' AND paid_at < now() - interval '15 minutes'
        """)
        report(ordersPaidUnlogged, "FULFILLED order(s) without a payment-log entry", """
            SELECT o.id FROM payment_orders o
            WHERE o.status = 'FULFILLED'
              AND o.fulfilled_at < now() - interval '15 minutes'
              AND NOT EXISTS (
                  SELECT 1 FROM subscription_payment_log l
                  WHERE l.order_id = o.id
                     OR l.transaction_id = CAST(o.p24_order_id AS TEXT)
                     OR l.transaction_id = o.session_id
              )
        """)
        report(ordersRefundRequired, "order(s) paid but impossible to fulfil — refund required", """
            SELECT id FROM payment_orders WHERE status = 'REFUND_REQUIRED'
        """)
        report(notificationsNeedsReview, "payment notification(s) needing manual review", """
            SELECT id FROM payment_notifications WHERE status = 'NEEDS_REVIEW'
        """)
    }

    private fun report(gauge: AtomicLong, what: String, sql: String) {
        val ids = jdbcTemplate.query(sql.trimIndent()) { rs, _ -> rs.getObject("id", UUID::class.java) }
        gauge.set(ids.size.toLong())
        if (ids.isNotEmpty()) {
            logger.error("RECONCILIATION: {} {}: {}", ids.size, what, ids.take(50))
        }
    }
}
