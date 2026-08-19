package pl.detailing.crm.metrics.query

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import pl.detailing.crm.metrics.domain.MetricsClock
import java.time.LocalDate

/**
 * The retention board: every tenant ranked by how likely we are to lose them.
 *
 * ## Why revenue-weighted, not score-ordered
 *
 * A list sorted purely by health score puts a 39 zł trial account above a 400 zł studio
 * that just halved its usage. The board sorts by risk **and then by revenue at stake**,
 * because the point of the screen is to decide who gets a phone call this week, and
 * account-management time is the scarce resource being allocated.
 *
 * ## Why the signals are spelled out
 *
 * A bare number ("health: 34") tells whoever opens this nothing about what to say on that
 * call. Each row carries the specific reasons its score is low, in plain language, ranked
 * by contribution — "nie logowali się od 12 dni", "aktywność spadła o 68%". That is the
 * difference between a dashboard people look at and one they act on.
 */
@Service
class GetTenantHealthHandler(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    fun handle(date: LocalDate = MetricsClock.today(), riskFilter: String? = null): List<TenantHealthRow> {
        val params = MapSqlParameterSource()
            .addValue("day", java.sql.Date.valueOf(date))
            .addValue("recentFrom", java.sql.Date.valueOf(date.minusDays(13)))
            .addValue("priorFrom", java.sql.Date.valueOf(date.minusDays(27)))
            .addValue("priorTo", java.sql.Date.valueOf(date.minusDays(14)))
            .addValue("risk", riskFilter?.uppercase())

        return jdbcTemplate.query(HEALTH_SQL, params) { rs, _ ->
            val recent = rs.getLong("recent_minutes")
            val prior = rs.getLong("prior_minutes")
            val daysSince = rs.getObject("days_since_activity")?.let { (it as Number).toLong() }
            val seatsTotal = rs.getInt("seats_total")
            val seatsActive = rs.getInt("seats_active")
            val reservations = rs.getLong("recent_reservations")

            val trendPercent = when {
                prior == 0L && recent == 0L -> 0.0
                prior == 0L -> 100.0
                else -> (recent - prior) * 100.0 / prior
            }

            TenantHealthRow(
                studioId = rs.getString("studio_id"),
                studioName = rs.getString("studio_name"),
                planKey = rs.getString("plan_key") ?: "NONE",
                subscriptionStatus = rs.getString("subscription_status") ?: "UNKNOWN",
                healthScore = rs.getInt("health_score"),
                churnRisk = rs.getString("churn_risk") ?: "UNKNOWN",
                daysSinceLastActivity = daysSince,
                activeMinutes14d = recent,
                activeMinutesPrior14d = prior,
                trendPercent = trendPercent,
                reservations14d = reservations,
                seatsTotal = seatsTotal,
                seatsActive = seatsActive,
                mrrGrossCents = rs.getLong("mrr_gross_cents"),
                signals = signals(daysSince, trendPercent, reservations, seatsTotal, seatsActive, rs.getLong("errors_14d"))
            )
        }
    }

    /**
     * Turns the score's inputs back into sentences. Ordered by how much each dragged the
     * score down, so the first line is the thing to open the conversation with.
     */
    internal fun signals(
        daysSinceActivity: Long?,
        trendPercent: Double,
        reservations14d: Long,
        seatsTotal: Int,
        seatsActive: Int,
        errors14d: Long
    ): List<String> = buildList {
        when {
            daysSinceActivity == null -> add("Brak jakiejkolwiek zarejestrowanej aktywności.")
            daysSinceActivity >= 30 -> add("Brak logowania od $daysSinceActivity dni — konto praktycznie porzucone.")
            daysSinceActivity >= 14 -> add("Brak logowania od $daysSinceActivity dni.")
            daysSinceActivity >= 7 -> add("Ostatnia aktywność $daysSinceActivity dni temu.")
        }

        when {
            trendPercent <= -50 -> add("Czas pracy w systemie spadł o ${-trendPercent.toInt()}% wzgl. poprzednich 2 tygodni.")
            trendPercent <= -25 -> add("Spadek aktywności o ${-trendPercent.toInt()}%.")
            trendPercent >= 50 -> add("Wzrost aktywności o ${trendPercent.toInt()}% — dobry moment na rozmowę o rozbudowie pakietu.")
        }

        if (reservations14d == 0L) {
            add("Zero rezerwacji w ostatnich 2 tygodniach — studio prowadzi biznes poza systemem.")
        } else if (reservations14d < 4) {
            add("Tylko $reservations14d rezerwacji w 2 tygodnie.")
        }

        if (seatsTotal > 1 && seatsActive * 2 <= seatsTotal) {
            add("Wykorzystanie kont: $seatsActive z $seatsTotal — pracownicy nie korzystają z systemu.")
        }

        if (errors14d > 25) {
            add("$errors14d błędów dotknęło to studio w 2 tygodnie — problem po naszej stronie.")
        }

        if (isEmpty()) add("Brak sygnałów ostrzegawczych.")
    }

    companion object {
        private val HEALTH_SQL = """
            SELECT
                s.id AS studio_id,
                s.name AS studio_name,
                today.plan_key,
                today.subscription_status,
                COALESCE(today.health_score, 0) AS health_score,
                COALESCE(today.churn_risk, 'UNKNOWN') AS churn_risk,
                COALESCE(today.mrr_gross_cents, 0) AS mrr_gross_cents,
                COALESCE(today.users_total, 0) AS seats_total,
                (SELECT COUNT(DISTINCT ms.user_id) FROM metric_user_sessions ms
                 WHERE ms.studio_id = s.id AND ms.is_meaningful = true
                   AND ms.session_date >= :recentFrom) AS seats_active,
                EXTRACT(DAY FROM (now() - (SELECT MAX(ms.last_activity_at)
                                           FROM metric_user_sessions ms
                                           WHERE ms.studio_id = s.id)))::bigint AS days_since_activity,
                COALESCE((SELECT SUM(d.active_minutes_total) FROM metric_daily_studio_snapshots d
                          WHERE d.studio_id = s.id AND d.snapshot_date >= :recentFrom
                            AND d.snapshot_date <= :day), 0) AS recent_minutes,
                COALESCE((SELECT SUM(d.active_minutes_total) FROM metric_daily_studio_snapshots d
                          WHERE d.studio_id = s.id AND d.snapshot_date >= :priorFrom
                            AND d.snapshot_date < :priorTo), 0) AS prior_minutes,
                COALESCE((SELECT SUM(d.reservations_created) FROM metric_daily_studio_snapshots d
                          WHERE d.studio_id = s.id AND d.snapshot_date >= :recentFrom
                            AND d.snapshot_date <= :day), 0) AS recent_reservations,
                COALESCE((SELECT SUM(d.errors_total) FROM metric_daily_studio_snapshots d
                          WHERE d.studio_id = s.id AND d.snapshot_date >= :recentFrom
                            AND d.snapshot_date <= :day), 0) AS errors_14d
            FROM studios s
            LEFT JOIN metric_daily_studio_snapshots today
                   ON today.studio_id = s.id AND today.snapshot_date = :day
            WHERE (:risk IS NULL OR COALESCE(today.churn_risk, 'UNKNOWN') = :risk)
            -- Risk first, then money at stake: the board allocates account-management time,
            -- and a 39 zł trial above a 400 zł studio in decline gets that backwards.
            ORDER BY
                CASE COALESCE(today.churn_risk, 'UNKNOWN')
                    WHEN 'CRITICAL' THEN 0 WHEN 'AT_RISK' THEN 1
                    -- UNKNOWN above WATCH on purpose: a row the calculator never scored is
                    -- a gap in the board, and burying it at the bottom is how it stays a gap.
                    WHEN 'UNKNOWN' THEN 2 WHEN 'WATCH' THEN 3 ELSE 4 END,
                COALESCE(today.mrr_gross_cents, 0) DESC,
                COALESCE(today.health_score, 0)
        """.trimIndent()
    }
}
