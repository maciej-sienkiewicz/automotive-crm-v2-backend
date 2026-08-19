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
 * Aggregates the per-tenant snapshots into one platform-wide row per day.
 *
 * Runs after [DailyStudioRollupJob] and reads its output rather than the raw tables, so
 * the two can never disagree — "the sum of our customers' reservations" and "reservations
 * on the platform" being two different numbers is a credibility problem that takes a week
 * to live down and is entirely avoidable by summing the same source.
 *
 * DAU/WAU/MAU and stickiness are computed here because they are inherently multi-day and
 * cross-tenant: no per-studio row can know how many *other* studios were active this month.
 */
@Component
class DailyPlatformRollupJob(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val properties: MetricsProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 03:25 — after the studio roll-up at 03:10, before retention at 03:40. */
    @Scheduled(cron = "0 25 3 * * *")
    fun runNightly() {
        rollupFor(MetricsClock.yesterday())
        rollupFor(MetricsClock.today())
    }

    @Scheduled(cron = "0 15 * * * *")
    fun runHourly() {
        rollupFor(MetricsClock.today())
    }

    @Transactional
    fun rollupFor(date: LocalDate) {
        if (!properties.enabled) return

        try {
            val params = MapSqlParameterSource()
                .addValue("day", java.sql.Date.valueOf(date))
                // Bound explicitly rather than written as `:day - 1` in SQL: a bound
                // parameter in an arithmetic expression gives Postgres nothing to infer
                // the type from, and the statement fails at execution, not at review.
                .addValue("prevDay", java.sql.Date.valueOf(date.minusDays(1)))
                .addValue("weekFrom", java.sql.Date.valueOf(date.minusDays(6)))
                .addValue("monthFrom", java.sql.Date.valueOf(date.minusDays(29)))
                .addValue("from", Timestamp.from(MetricsClock.startOf(date)))
                .addValue("to", Timestamp.from(MetricsClock.endOf(date)))

            jdbcTemplate.update(UPSERT_SQL, params)
            log.info("Agregacja platformowa {} zakończona", date)
        } catch (ex: Exception) {
            log.error("Agregacja platformowa dla {} nie powiodła się: {}", date, ex.message, ex)
        }
    }

    companion object {
        private val UPSERT_SQL = """
            INSERT INTO metric_daily_platform_snapshots (
                snapshot_date, studios_total, studios_paying, studios_trialing, studios_expired,
                studios_plan_basic, studios_plan_full, new_signups, churned,
                mrr_gross_cents, arpa_gross_cents,
                dau_studios, wau_studios, mau_studios, dau_users, stickiness_permille,
                active_minutes_total, reservations_created, visits_completed,
                sms_sent, emails_sent, api_calls, errors_total, error_groups_new, studios_with_errors,
                computed_at
            )
            SELECT
                :day,
                COUNT(*),
                COUNT(*) FILTER (WHERE d.subscription_status IN ('ACTIVE', 'PAST_DUE')),
                COUNT(*) FILTER (WHERE d.subscription_status = 'TRIALING'),
                COUNT(*) FILTER (WHERE d.subscription_status = 'EXPIRED'),
                COUNT(*) FILTER (WHERE d.plan_key = 'BASIC'),
                COUNT(*) FILTER (WHERE d.plan_key = 'FULL'),

                (SELECT COUNT(*) FROM studios s WHERE s.created_at >= :from AND s.created_at < :to),

                -- Churn is a transition, not a state: a studio counts as churned on the day
                -- it stopped being active, which requires yesterday's row to compare against.
                (SELECT COUNT(*) FROM metric_daily_studio_snapshots prev
                 WHERE prev.snapshot_date = :prevDay
                   AND prev.subscription_status IN ('ACTIVE', 'PAST_DUE')
                   AND EXISTS (SELECT 1 FROM metric_daily_studio_snapshots cur
                               WHERE cur.studio_id = prev.studio_id
                                 AND cur.snapshot_date = :day
                                 AND cur.subscription_status = 'EXPIRED')),

                COALESCE(SUM(d.mrr_gross_cents), 0),
                CASE WHEN COUNT(*) FILTER (WHERE d.subscription_status IN ('ACTIVE', 'PAST_DUE')) > 0
                     THEN COALESCE(SUM(d.mrr_gross_cents), 0)
                          / COUNT(*) FILTER (WHERE d.subscription_status IN ('ACTIVE', 'PAST_DUE'))
                     ELSE 0 END,

                COUNT(*) FILTER (WHERE d.active_minutes_total > 0),

                (SELECT COUNT(DISTINCT w.studio_id) FROM metric_daily_studio_snapshots w
                 WHERE w.snapshot_date BETWEEN :weekFrom AND :day AND w.active_minutes_total > 0),
                (SELECT COUNT(DISTINCT m.studio_id) FROM metric_daily_studio_snapshots m
                 WHERE m.snapshot_date BETWEEN :monthFrom AND :day AND m.active_minutes_total > 0),

                COALESCE(SUM(d.users_active), 0),

                -- Stickiness (DAU/MAU) in per-mille so the column stays integral.
                -- The classic engagement ratio: how much of the customer base that shows
                -- up in a month shows up on any given day.
                CASE WHEN (SELECT COUNT(DISTINCT m.studio_id) FROM metric_daily_studio_snapshots m
                           WHERE m.snapshot_date BETWEEN :monthFrom AND :day
                             AND m.active_minutes_total > 0) > 0
                     THEN (COUNT(*) FILTER (WHERE d.active_minutes_total > 0) * 1000)
                          / (SELECT COUNT(DISTINCT m.studio_id) FROM metric_daily_studio_snapshots m
                             WHERE m.snapshot_date BETWEEN :monthFrom AND :day
                               AND m.active_minutes_total > 0)
                     ELSE 0 END,

                COALESCE(SUM(d.active_minutes_total), 0),
                COALESCE(SUM(d.reservations_created), 0),
                COALESCE(SUM(d.visits_completed), 0),
                COALESCE(SUM(d.sms_sent), 0),
                COALESCE(SUM(d.emails_sent), 0),
                COALESCE(SUM(d.api_calls), 0),
                COALESCE(SUM(d.errors_total), 0),

                (SELECT COUNT(*) FROM metric_error_groups g
                 WHERE g.first_seen_at >= :from AND g.first_seen_at < :to),

                COUNT(*) FILTER (WHERE d.errors_total > 0),

                now()
            FROM metric_daily_studio_snapshots d
            WHERE d.snapshot_date = :day
            ON CONFLICT (snapshot_date) DO UPDATE SET
                studios_total        = EXCLUDED.studios_total,
                studios_paying       = EXCLUDED.studios_paying,
                studios_trialing     = EXCLUDED.studios_trialing,
                studios_expired      = EXCLUDED.studios_expired,
                studios_plan_basic   = EXCLUDED.studios_plan_basic,
                studios_plan_full    = EXCLUDED.studios_plan_full,
                new_signups          = EXCLUDED.new_signups,
                churned              = EXCLUDED.churned,
                mrr_gross_cents      = EXCLUDED.mrr_gross_cents,
                arpa_gross_cents     = EXCLUDED.arpa_gross_cents,
                dau_studios          = EXCLUDED.dau_studios,
                wau_studios          = EXCLUDED.wau_studios,
                mau_studios          = EXCLUDED.mau_studios,
                dau_users            = EXCLUDED.dau_users,
                stickiness_permille  = EXCLUDED.stickiness_permille,
                active_minutes_total = EXCLUDED.active_minutes_total,
                reservations_created = EXCLUDED.reservations_created,
                visits_completed     = EXCLUDED.visits_completed,
                sms_sent             = EXCLUDED.sms_sent,
                emails_sent          = EXCLUDED.emails_sent,
                api_calls            = EXCLUDED.api_calls,
                errors_total         = EXCLUDED.errors_total,
                error_groups_new     = EXCLUDED.error_groups_new,
                studios_with_errors  = EXCLUDED.studios_with_errors,
                computed_at          = now()
        """.trimIndent()
    }
}
