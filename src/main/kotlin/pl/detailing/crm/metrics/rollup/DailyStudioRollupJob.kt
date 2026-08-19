package pl.detailing.crm.metrics.rollup

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.metrics.config.MetricsProperties
import pl.detailing.crm.metrics.domain.MetricsClock
import java.sql.Timestamp
import java.time.LocalDate

/**
 * Builds one row per tenant per day — the table every console screen reads.
 *
 * ## Why roll up at all
 *
 * A dashboard that aggregates raw events on each page load is fast on launch day and
 * unusable by the second quarter, because its cost grows with the history it covers.
 * A snapshot's cost is fixed: "usage per studio for the last twelve months" is a 365-row
 * index scan whether the platform is two months or five years old.
 *
 * ## Idempotent by construction
 *
 * The statement is an upsert keyed on `(studio_id, snapshot_date)`, and every value is
 * recomputed from source rather than incremented. Re-running it for the same day — after
 * a crash, a fixed bug or a manual backfill — produces the same row. A roll-up that
 * accumulated instead would double the numbers on every retry, and the corruption would
 * be silent and permanent.
 *
 * ## Where the numbers come from
 *
 * Counts of things that own a table (`appointments`, `visits`) are read from that table,
 * not from the event stream: exact, backfillable across the whole existing history, and
 * incapable of drifting from what the business itself sees. The event stream supplies
 * only what leaves no other trace — SMS, e-mail — and the session table supplies engaged
 * time. See `BusinessActivityAspect` for the full rationale.
 */
@Component
class DailyStudioRollupJob(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val healthCalculator: TenantHealthCalculator,
    private val properties: MetricsProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 03:10 — after midnight in Europe/Warsaw so the day is closed, and before the
     * retention job at 03:40 so nothing is purged before it has been rolled up.
     */
    @Scheduled(cron = "0 10 3 * * *")
    fun runNightly() {
        rollupFor(MetricsClock.yesterday())
        // Today's partial row keeps the console current during the day. Recomputed by the
        // nightly pass, so a partial number is never mistaken for a final one.
        rollupFor(MetricsClock.today())
    }

    /** Intra-day refresh so the console is never more than an hour stale. */
    @Scheduled(cron = "0 5 * * * *")
    fun runHourly() {
        rollupFor(MetricsClock.today())
    }

    @Transactional
    fun rollupFor(date: LocalDate) {
        if (!properties.enabled) return

        val started = System.currentTimeMillis()
        try {
            val from = Timestamp.from(MetricsClock.startOf(date))
            val to = Timestamp.from(MetricsClock.endOf(date))
            val day = java.sql.Date.valueOf(date)

            // Named parameters, not positional: the statement below binds the same three
            // values across twenty-odd correlated sub-selects, and a positional list that
            // drifts by one silently reports the wrong day rather than failing.
            val params = MapSqlParameterSource()
                .addValue("day", day)
                .addValue("from", from)
                .addValue("to", to)

            val affected = jdbcTemplate.update(UPSERT_SQL, params)
            healthCalculator.recomputeFor(date)

            log.info(
                "Agregacja dzienna {} — {} studiów, {} ms",
                date, affected, System.currentTimeMillis() - started
            )
        } catch (ex: Exception) {
            log.error("Agregacja dzienna dla {} nie powiodła się: {}", date, ex.message, ex)
        }
    }

    companion object {
        /**
         * Correlated sub-selects rather than a chain of LEFT JOINs: each aggregate has a
         * different grain, and joining them would multiply rows (the classic fan-out that
         * turns three sessions × four errors into twelve of each). Postgres evaluates each
         * sub-select once per studio against an index on `(studio_id, date)`, and at this
         * platform's tenant count the plan is trivially fast.
         */
        private val UPSERT_SQL = """
            INSERT INTO metric_daily_studio_snapshots (
                id, studio_id, snapshot_date, plan_key, subscription_status,
                active_add_ons, mrr_gross_cents, users_total, users_active, sessions_count,
                active_minutes_total, active_minutes_owner, active_minutes_employee, api_calls,
                reservations_created, visits_created, visits_completed, logins,
                sms_sent, emails_sent, sms_credits_remaining,
                errors_total, errors_critical, avg_latency_ms,
                health_score, churn_risk, last_activity_at, computed_at
            )
            SELECT
                gen_random_uuid(),
                s.id,
                :day,

                COALESCE((SELECT p.plan_key FROM studio_subscription_plans ssp
                          JOIN subscription_plans p ON p.id = ssp.plan_id
                          WHERE ssp.studio_id = s.id), 'NONE'),
                s.subscription_status,

                COALESCE((SELECT COUNT(*) FROM studio_subscription_plans ssp
                          JOIN studio_subscription_add_ons saa ON saa.studio_subscription_plan_id = ssp.id
                          WHERE ssp.studio_id = s.id), 0),

                -- Revenue is recognised only for genuinely paying accounts. A trial is not
                -- revenue, and a snapshot that counts it as such makes every MRR chart lie.
                CASE WHEN s.subscription_status IN ('ACTIVE', 'PAST_DUE') THEN
                    COALESCE((SELECT p.monthly_price_gross_cents FROM studio_subscription_plans ssp
                              JOIN subscription_plans p ON p.id = ssp.plan_id
                              WHERE ssp.studio_id = s.id), 0)
                    + COALESCE((SELECT SUM(a.monthly_price_gross_cents) FROM studio_subscription_plans ssp
                                JOIN studio_subscription_add_ons saa ON saa.studio_subscription_plan_id = ssp.id
                                JOIN subscription_add_ons a ON a.id = saa.add_on_id
                                WHERE ssp.studio_id = s.id AND a.monthly_price_gross_cents IS NOT NULL), 0)
                ELSE 0 END,

                (SELECT COUNT(*) FROM users u WHERE u.studio_id = s.id AND u.is_active = true),

                -- Only meaningful sessions count as "active": the whole point of the
                -- is_meaningful flag is that the exclusion happens once, not per query.
                COALESCE((SELECT COUNT(DISTINCT ms.user_id) FROM metric_user_sessions ms
                          WHERE ms.studio_id = s.id AND ms.session_date = :day
                            AND ms.is_meaningful = true), 0),
                COALESCE((SELECT COUNT(*) FROM metric_user_sessions ms
                          WHERE ms.studio_id = s.id AND ms.session_date = :day
                            AND ms.is_meaningful = true), 0),

                COALESCE((SELECT SUM(ms.active_seconds) / 60 FROM metric_user_sessions ms
                          WHERE ms.studio_id = s.id AND ms.session_date = :day
                            AND ms.is_meaningful = true), 0),
                COALESCE((SELECT SUM(ms.active_seconds) / 60 FROM metric_user_sessions ms
                          WHERE ms.studio_id = s.id AND ms.session_date = :day
                            AND ms.is_meaningful = true AND ms.actor_kind = 'OWNER'), 0),
                COALESCE((SELECT SUM(ms.active_seconds) / 60 FROM metric_user_sessions ms
                          WHERE ms.studio_id = s.id AND ms.session_date = :day
                            AND ms.is_meaningful = true AND ms.actor_kind = 'EMPLOYEE'), 0),

                COALESCE((SELECT SUM(sad.call_count) FROM metric_studio_api_daily sad
                          WHERE sad.studio_id = s.id AND sad.usage_date = :day), 0),

                COALESCE((SELECT COUNT(*) FROM appointments a
                          WHERE a.studio_id = s.id AND a.created_at >= :from AND a.created_at < :to), 0),

                COALESCE((SELECT COUNT(*) FROM visits v
                          WHERE v.studio_id = s.id AND v.deleted_at IS NULL
                            AND v.created_at >= :from AND v.created_at < :to), 0),

                -- Completion is the pickup, not the status flag: a visit can sit in
                -- COMPLETED for days, and counting it on the wrong day misaligns the
                -- throughput chart with the studio's own sense of what it finished when.
                COALESCE((SELECT COUNT(*) FROM visits v
                          WHERE v.studio_id = s.id AND v.deleted_at IS NULL
                            AND v.pickup_date >= :from AND v.pickup_date < :to), 0),

                COALESCE((SELECT COUNT(*) FROM audit_logs al
                          WHERE al.studio_id = s.id AND al.action = 'LOGIN_SUCCESS'
                            AND al.created_at >= :from AND al.created_at < :to), 0),

                COALESCE((SELECT SUM(me.quantity) FROM metric_events me
                          WHERE me.studio_id = s.id AND me.event_date = :day
                            AND me.event_type = 'SMS_SENT'), 0),
                COALESCE((SELECT SUM(me.quantity) FROM metric_events me
                          WHERE me.studio_id = s.id AND me.event_date = :day
                            AND me.event_type = 'EMAIL_SENT'), 0),

                COALESCE((SELECT scb.available_credits FROM sms_credit_balances scb
                          WHERE scb.studio_id = s.id), 0),

                COALESCE((SELECT COUNT(*) FROM metric_error_events ee
                          WHERE ee.studio_id = s.id AND ee.occurred_at >= :from AND ee.occurred_at < :to), 0),
                COALESCE((SELECT COUNT(*) FROM metric_error_events ee
                          WHERE ee.studio_id = s.id AND ee.severity = 'CRITICAL'
                            AND ee.occurred_at >= :from AND ee.occurred_at < :to), 0),

                COALESCE((SELECT CASE WHEN SUM(sad.call_count) > 0
                                      THEN SUM(sad.total_duration_ms) / SUM(sad.call_count)
                                      ELSE 0 END
                          FROM metric_studio_api_daily sad
                          WHERE sad.studio_id = s.id AND sad.usage_date = :day), 0),

                -- Health is a second pass: it needs a trend across days, which a
                -- single-day statement cannot see. See TenantHealthCalculator.
                0,
                'HEALTHY',

                (SELECT MAX(ms.last_activity_at) FROM metric_user_sessions ms
                 WHERE ms.studio_id = s.id),

                now()
            FROM studios s
            ON CONFLICT (studio_id, snapshot_date) DO UPDATE SET
                plan_key                = EXCLUDED.plan_key,
                subscription_status     = EXCLUDED.subscription_status,
                active_add_ons          = EXCLUDED.active_add_ons,
                mrr_gross_cents         = EXCLUDED.mrr_gross_cents,
                users_total             = EXCLUDED.users_total,
                users_active            = EXCLUDED.users_active,
                sessions_count          = EXCLUDED.sessions_count,
                active_minutes_total    = EXCLUDED.active_minutes_total,
                active_minutes_owner    = EXCLUDED.active_minutes_owner,
                active_minutes_employee = EXCLUDED.active_minutes_employee,
                api_calls               = EXCLUDED.api_calls,
                reservations_created    = EXCLUDED.reservations_created,
                visits_created          = EXCLUDED.visits_created,
                visits_completed        = EXCLUDED.visits_completed,
                logins                  = EXCLUDED.logins,
                sms_sent                = EXCLUDED.sms_sent,
                emails_sent             = EXCLUDED.emails_sent,
                sms_credits_remaining   = EXCLUDED.sms_credits_remaining,
                errors_total            = EXCLUDED.errors_total,
                errors_critical         = EXCLUDED.errors_critical,
                avg_latency_ms          = EXCLUDED.avg_latency_ms,
                last_activity_at        = EXCLUDED.last_activity_at,
                computed_at             = now()
        """.trimIndent()
    }
}
