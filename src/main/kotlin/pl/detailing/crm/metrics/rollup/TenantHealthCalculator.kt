package pl.detailing.crm.metrics.rollup

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.metrics.domain.ChurnRisk
import java.time.LocalDate

/**
 * Scores every tenant 0–100 on how likely they are to still be a customer next quarter.
 *
 * ## Why a provider of a CRM needs this
 *
 * Churn in a small-business SaaS is almost never announced. A studio does not write to
 * cancel — it quietly stops logging in, keeps paying for two months out of inertia, and
 * then disputes a renewal. Every one of those endings is visible in usage data weeks
 * before it reaches billing, and this score exists to surface it while a phone call can
 * still change the outcome.
 *
 * ## The five signals, and why each is here
 *
 * | Signal | Weight | What it catches |
 * |---|---|---|
 * | Recency of use | 30 | The single strongest predictor. Nobody churns while logging in daily. |
 * | Engagement trend (14d vs prior 14d) | 25 | Catches decline *before* it becomes absence — the actionable window. |
 * | Business output (reservations) | 20 | Separates "logs in" from "runs the business here". A CRM with no bookings is a contact list. |
 * | Seat utilisation | 15 | Paying for six seats and using two is the conversation that precedes a downgrade. |
 * | Error exposure | 10 | Our own fault, and the one factor on this list we control directly. |
 *
 * Weights are a deliberate, reviewable product judgement, not a fitted model: with a
 * customer base this size there is not enough churn data to fit anything meaningful, and
 * a transparent rubric someone can argue with beats a black box nobody trusts. When
 * enough churn events accumulate, the honest upgrade is to regress these same features
 * against actual outcomes — the inputs are already stored per day.
 *
 * A studio in its first week has no trend to measure, so new accounts start neutral
 * rather than scoring as critical: flagging every new signup as at-risk is how a
 * retention report gets ignored.
 */
@Component
class TenantHealthCalculator(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun recomputeFor(date: LocalDate) {
        try {
            val rows = jdbcTemplate.query(
                INPUTS_SQL,
                MapSqlParameterSource()
                    .addValue("day", java.sql.Date.valueOf(date))
                    .addValue("recentFrom", java.sql.Date.valueOf(date.minusDays(13)))
                    .addValue("priorFrom", java.sql.Date.valueOf(date.minusDays(27)))
                    .addValue("priorTo", java.sql.Date.valueOf(date.minusDays(14)))
            ) { rs, _ ->
                HealthInputs(
                    studioId = rs.getObject("studio_id", java.util.UUID::class.java),
                    daysSinceActivity = rs.getInt("days_since_activity"),
                    recentMinutes = rs.getLong("recent_minutes"),
                    priorMinutes = rs.getLong("prior_minutes"),
                    recentReservations = rs.getLong("recent_reservations"),
                    usersTotal = rs.getInt("users_total"),
                    usersActive14d = rs.getInt("users_active_14d"),
                    errors14d = rs.getLong("errors_14d"),
                    accountAgeDays = rs.getInt("account_age_days")
                )
            }

            rows.forEach { inputs ->
                val score = score(inputs)
                jdbcTemplate.update(
                    """
                    UPDATE metric_daily_studio_snapshots
                    SET health_score = :score, churn_risk = :risk
                    WHERE studio_id = :studioId AND snapshot_date = :day
                    """.trimIndent(),
                    MapSqlParameterSource()
                        .addValue("score", score)
                        .addValue("risk", ChurnRisk.fromScore(score).name)
                        .addValue("studioId", inputs.studioId)
                        .addValue("day", java.sql.Date.valueOf(date))
                )
            }

            log.debug("Przeliczono health score dla {} studiów ({})", rows.size, date)
        } catch (ex: Exception) {
            log.error("Przeliczenie health score dla {} nie powiodło się: {}", date, ex.message, ex)
        }
    }

    /** Pure function of the inputs — trivially unit-testable, and tested. */
    internal fun score(i: HealthInputs): Int {
        // A brand-new account has nothing to trend against. Neutral, not critical:
        // a report that flags every signup as at-risk teaches everyone to ignore it.
        if (i.accountAgeDays < 14) return 60

        val recency = when {
            i.daysSinceActivity <= 1 -> 30
            i.daysSinceActivity <= 3 -> 25
            i.daysSinceActivity <= 7 -> 18
            i.daysSinceActivity <= 14 -> 10
            i.daysSinceActivity <= 30 -> 4
            else -> 0
        }

        val trend = when {
            // No prior baseline: a studio that only started using the product now is
            // improving, not declining.
            i.priorMinutes == 0L && i.recentMinutes > 0 -> 25
            i.priorMinutes == 0L -> 5
            else -> {
                val ratio = i.recentMinutes.toDouble() / i.priorMinutes
                when {
                    ratio >= 1.1 -> 25
                    ratio >= 0.9 -> 20
                    ratio >= 0.7 -> 14
                    ratio >= 0.4 -> 7
                    else -> 0
                }
            }
        }

        // Output, not just presence. Logging in without booking anything means the studio
        // is running its business somewhere else and keeping this tab open out of habit.
        val output = when {
            i.recentReservations >= 20 -> 20
            i.recentReservations >= 10 -> 16
            i.recentReservations >= 4 -> 11
            i.recentReservations >= 1 -> 6
            else -> 0
        }

        val seats = when {
            i.usersTotal <= 0 -> 8
            else -> {
                val utilisation = i.usersActive14d.toDouble() / i.usersTotal
                when {
                    utilisation >= 0.8 -> 15
                    utilisation >= 0.5 -> 11
                    utilisation >= 0.25 -> 6
                    else -> 2
                }
            }
        }

        // Our own contribution to their experience. Capped at 10 so a bad deploy dents
        // the score without swamping the retention signals it sits next to.
        val reliability = when {
            i.errors14d == 0L -> 10
            i.errors14d <= 5 -> 8
            i.errors14d <= 25 -> 5
            i.errors14d <= 100 -> 2
            else -> 0
        }

        return (recency + trend + output + seats + reliability).coerceIn(0, 100)
    }

    data class HealthInputs(
        val studioId: java.util.UUID,
        val daysSinceActivity: Int,
        val recentMinutes: Long,
        val priorMinutes: Long,
        val recentReservations: Long,
        val usersTotal: Int,
        val usersActive14d: Int,
        val errors14d: Long,
        val accountAgeDays: Int
    )

    companion object {
        /**
         * Reads every input from the snapshot table itself. The roll-up has already run for
         * this day, so the trend windows are a scan over at most 28 pre-aggregated rows per
         * tenant — the reason the score is cheap enough to recompute nightly for everyone.
         */
        private val INPUTS_SQL = """
            SELECT
                s.id AS studio_id,
                COALESCE(EXTRACT(DAY FROM (now() - MAX(snap.last_activity_at)))::int, 999) AS days_since_activity,
                COALESCE(SUM(CASE WHEN snap.snapshot_date >= :recentFrom
                                  THEN snap.active_minutes_total ELSE 0 END), 0) AS recent_minutes,
                COALESCE(SUM(CASE WHEN snap.snapshot_date >= :priorFrom AND snap.snapshot_date < :priorTo
                                  THEN snap.active_minutes_total ELSE 0 END), 0) AS prior_minutes,
                COALESCE(SUM(CASE WHEN snap.snapshot_date >= :recentFrom
                                  THEN snap.reservations_created ELSE 0 END), 0) AS recent_reservations,
                COALESCE(MAX(CASE WHEN snap.snapshot_date = :day THEN snap.users_total END), 0) AS users_total,
                COALESCE(MAX(CASE WHEN snap.snapshot_date >= :recentFrom
                                  THEN snap.users_active ELSE 0 END), 0) AS users_active_14d,
                COALESCE(SUM(CASE WHEN snap.snapshot_date >= :recentFrom
                                  THEN snap.errors_total ELSE 0 END), 0) AS errors_14d,
                COALESCE(EXTRACT(DAY FROM (now() - s.created_at))::int, 0) AS account_age_days
            FROM studios s
            LEFT JOIN metric_daily_studio_snapshots snap
                   ON snap.studio_id = s.id
                  AND snap.snapshot_date >= :priorFrom
                  AND snap.snapshot_date <= :day
            GROUP BY s.id, s.created_at
        """.trimIndent()
    }
}
