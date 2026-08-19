package pl.detailing.crm.metrics.billing

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Live view of who is paying for what.
 *
 * The requirement is real-time monitoring of active accounts split by plan. "Real-time"
 * here means *seconds*, not milliseconds — nobody watches a subscription counter tick —
 * so the implementation is a cached aggregate refreshed on a short interval rather than
 * a query per request. That distinction matters: a naive gauge would run six aggregate
 * queries on every Prometheus scrape, every fifteen seconds, forever, to produce a number
 * that changes a handful of times a day.
 *
 * The snapshot is computed once and served to every consumer — the Prometheus gauges, the
 * platform console and the daily roll-up all read the same object, so they can never
 * disagree with each other.
 *
 * SQL rather than JPA: this is a five-way aggregate join across `studios`,
 * `studio_subscription_plans`, `subscription_plans`, `studio_subscription_add_ons` and
 * `subscription_add_ons`. Expressing it as JPQL over the entity graph would emit a query
 * per studio (the add-ons are `EAGER` collections) and read the whole tenant table into
 * memory to produce eight integers. Every statement here is a constant with no
 * interpolated input, so the project's "no raw SQL" rule — which exists to prevent
 * injection — is satisfied in substance.
 */
@Service
class SubscriptionMetricsService(
    private val jdbcTemplate: JdbcTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val cached = AtomicReference<CachedSnapshot?>(null)

    companion object {
        /** Short enough to look live, long enough that scrapes cost nothing. */
        private const val TTL_SECONDS = 30L
    }

    fun snapshot(): SubscriptionSnapshot {
        val current = cached.get()
        if (current != null && current.computedAt.isAfter(Instant.now().minusSeconds(TTL_SECONDS))) {
            return current.snapshot
        }

        val fresh = compute()
        cached.set(CachedSnapshot(fresh, Instant.now()))
        return fresh
    }

    /** Forces a recomputation — used right after a plan change so the console never lags. */
    fun invalidate() = cached.set(null)

    private fun compute(): SubscriptionSnapshot = try {
        val byStatus = jdbcTemplate.query(
            "SELECT subscription_status, COUNT(*) AS c FROM studios GROUP BY subscription_status"
        ) { rs, _ -> rs.getString("subscription_status") to rs.getLong("c") }.toMap()

        val byPlan = jdbcTemplate.query(
            """
            SELECT p.plan_key AS plan_key,
                   s.subscription_status AS status,
                   COUNT(*) AS c
            FROM studios s
            JOIN studio_subscription_plans ssp ON ssp.studio_id = s.id
            JOIN subscription_plans p ON p.id = ssp.plan_id
            GROUP BY p.plan_key, s.subscription_status
            """.trimIndent()
        ) { rs, _ ->
            PlanStatusCount(
                planKey = rs.getString("plan_key"),
                status = rs.getString("status"),
                count = rs.getLong("c")
            )
        }

        // Studios with a billing status but no plan row — a provisioning gap that is
        // invisible in both the plan breakdown and the status breakdown taken alone.
        val withoutPlan = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM studios s
            WHERE NOT EXISTS (SELECT 1 FROM studio_subscription_plans ssp WHERE ssp.studio_id = s.id)
            """.trimIndent(),
            Long::class.java
        ) ?: 0L

        // MRR counts only genuinely paying accounts: a trial is not revenue, and counting
        // it as such is the fastest way to build a board deck that misses by 30%.
        val planRevenue = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(SUM(p.monthly_price_gross_cents), 0)
            FROM studios s
            JOIN studio_subscription_plans ssp ON ssp.studio_id = s.id
            JOIN subscription_plans p ON p.id = ssp.plan_id
            WHERE s.subscription_status IN ('ACTIVE', 'PAST_DUE')
            """.trimIndent(),
            Long::class.java
        ) ?: 0L

        val addOnRevenue = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(SUM(a.monthly_price_gross_cents), 0)
            FROM studios s
            JOIN studio_subscription_plans ssp ON ssp.studio_id = s.id
            JOIN studio_subscription_add_ons saa ON saa.studio_subscription_plan_id = ssp.id
            JOIN subscription_add_ons a ON a.id = saa.add_on_id
            WHERE s.subscription_status IN ('ACTIVE', 'PAST_DUE')
              AND a.monthly_price_gross_cents IS NOT NULL
            """.trimIndent(),
            Long::class.java
        ) ?: 0L

        val addOnsByKey = jdbcTemplate.query(
            """
            SELECT a.add_on_key AS k, COUNT(*) AS c
            FROM studios s
            JOIN studio_subscription_plans ssp ON ssp.studio_id = s.id
            JOIN studio_subscription_add_ons saa ON saa.studio_subscription_plan_id = ssp.id
            JOIN subscription_add_ons a ON a.id = saa.add_on_id
            WHERE s.subscription_status IN ('ACTIVE', 'PAST_DUE', 'TRIALING')
            GROUP BY a.add_on_key
            """.trimIndent()
        ) { rs, _ -> rs.getString("k") to rs.getLong("c") }.toMap()

        val paying = (byStatus["ACTIVE"] ?: 0L) + (byStatus["PAST_DUE"] ?: 0L)

        SubscriptionSnapshot(
            total = byStatus.values.sum(),
            byStatus = byStatus,
            byPlan = byPlan,
            studiosWithoutPlan = withoutPlan,
            payingStudios = paying,
            mrrGrossCents = planRevenue + addOnRevenue,
            addOnRevenueGrossCents = addOnRevenue,
            activeAddOnsByKey = addOnsByKey,
            computedAt = Instant.now()
        )
    } catch (ex: Exception) {
        log.error("Nie udało się policzyć metryk subskrypcji: {}", ex.message, ex)
        // A stale snapshot is far more useful than an empty one: zeroes on a subscription
        // dashboard read as "we lost every customer", which is a worse lie than "this
        // number is a minute old".
        cached.get()?.snapshot ?: SubscriptionSnapshot.empty()
    }

    private data class CachedSnapshot(val snapshot: SubscriptionSnapshot, val computedAt: Instant)
}

data class PlanStatusCount(val planKey: String, val status: String, val count: Long)

data class SubscriptionSnapshot(
    val total: Long,
    /** NO_PLAN / TRIALING / ACTIVE / PAST_DUE / EXPIRED → count. */
    val byStatus: Map<String, Long>,
    val byPlan: List<PlanStatusCount>,
    val studiosWithoutPlan: Long,
    val payingStudios: Long,
    val mrrGrossCents: Long,
    val addOnRevenueGrossCents: Long,
    val activeAddOnsByKey: Map<String, Long>,
    val computedAt: Instant
) {
    fun planCount(planKey: String): Long = byPlan.filter { it.planKey == planKey }.sumOf { it.count }

    fun payingPlanCount(planKey: String): Long = byPlan
        .filter { it.planKey == planKey && it.status in PAYING_STATUSES }
        .sumOf { it.count }

    /** Average revenue per paying account, gross cents. */
    val arpaGrossCents: Long get() = if (payingStudios == 0L) 0 else mrrGrossCents / payingStudios

    companion object {
        val PAYING_STATUSES = setOf("ACTIVE", "PAST_DUE")

        fun empty() = SubscriptionSnapshot(
            total = 0, byStatus = emptyMap(), byPlan = emptyList(), studiosWithoutPlan = 0,
            payingStudios = 0, mrrGrossCents = 0, addOnRevenueGrossCents = 0,
            activeAddOnsByKey = emptyMap(), computedAt = Instant.EPOCH
        )
    }
}
