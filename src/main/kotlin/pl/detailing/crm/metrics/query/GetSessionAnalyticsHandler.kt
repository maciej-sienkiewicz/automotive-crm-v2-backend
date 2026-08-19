package pl.detailing.crm.metrics.query

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * Time-spent analytics, split by owner and employee, over a date range.
 *
 * Reads `metric_user_sessions` directly rather than the daily snapshot because this screen
 * needs distributions (median session length) and the owner/employee split at session
 * grain — things a pre-summed row cannot reconstruct. The range is bounded by an index on
 * `(studio_id, session_date)`, so the cost scales with the window, not the history.
 *
 * Both the meaningful and the discarded counts are reported. The discard ratio is a
 * quality metric for the measurement itself: if it moves sharply, the client-side
 * heartbeat has broken, and knowing that immediately beats discovering it a month later
 * when someone notices usage "grew" 40% without any new customers.
 */
@Service
class GetSessionAnalyticsHandler(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    fun handle(from: LocalDate, to: LocalDate, topN: Int = 20): SessionAnalyticsResponse {
        val params = MapSqlParameterSource()
            .addValue("from", java.sql.Date.valueOf(from))
            .addValue("to", java.sql.Date.valueOf(to))
            .addValue("limit", topN.coerceIn(1, 200))

        val totals = jdbcTemplate.queryForObject(TOTALS_SQL, params) { rs, _ ->
            Totals(
                activeSeconds = rs.getLong("active_seconds"),
                ownerSeconds = rs.getLong("owner_seconds"),
                employeeSeconds = rs.getLong("employee_seconds"),
                meaningful = rs.getLong("meaningful_sessions"),
                discarded = rs.getLong("discarded_sessions"),
                avgSeconds = rs.getLong("avg_seconds"),
                medianSeconds = rs.getLong("median_seconds")
            )
        } ?: Totals()

        val topStudios = jdbcTemplate.query(TOP_STUDIOS_SQL, params) { rs, _ ->
            StudioSessionRow(
                studioId = rs.getString("studio_id"),
                studioName = rs.getString("studio_name"),
                activeHours = rs.getLong("active_seconds") / 3600.0,
                ownerHours = rs.getLong("owner_seconds") / 3600.0,
                employeeHours = rs.getLong("employee_seconds") / 3600.0,
                sessions = rs.getLong("sessions"),
                activeUsers = rs.getInt("active_users")
            )
        }

        val totalSessions = totals.meaningful + totals.discarded

        return SessionAnalyticsResponse(
            from = from,
            to = to,
            totalActiveHours = totals.activeSeconds / 3600.0,
            ownerActiveHours = totals.ownerSeconds / 3600.0,
            employeeActiveHours = totals.employeeSeconds / 3600.0,
            meaningfulSessions = totals.meaningful,
            discardedSessions = totals.discarded,
            discardedRatioPercent =
                if (totalSessions == 0L) 0.0 else totals.discarded * 100.0 / totalSessions,
            avgSessionMinutes = totals.avgSeconds / 60,
            medianSessionMinutes = totals.medianSeconds / 60,
            topStudios = topStudios
        )
    }

    private data class Totals(
        val activeSeconds: Long = 0,
        val ownerSeconds: Long = 0,
        val employeeSeconds: Long = 0,
        val meaningful: Long = 0,
        val discarded: Long = 0,
        val avgSeconds: Long = 0,
        val medianSeconds: Long = 0
    )

    companion object {
        private val TOTALS_SQL = """
            SELECT
                COALESCE(SUM(active_seconds) FILTER (WHERE is_meaningful), 0)  AS active_seconds,
                COALESCE(SUM(active_seconds) FILTER (WHERE is_meaningful AND actor_kind = 'OWNER'), 0)    AS owner_seconds,
                COALESCE(SUM(active_seconds) FILTER (WHERE is_meaningful AND actor_kind = 'EMPLOYEE'), 0) AS employee_seconds,
                COUNT(*) FILTER (WHERE is_meaningful)      AS meaningful_sessions,
                COUNT(*) FILTER (WHERE NOT is_meaningful)  AS discarded_sessions,
                COALESCE(AVG(active_seconds) FILTER (WHERE is_meaningful), 0)::bigint AS avg_seconds,
                -- Median, not just mean: session length is heavily right-skewed (a handful
                -- of all-day power users drag the average far above what a typical session
                -- looks like), and quoting only the mean overstates normal engagement.
                COALESCE(PERCENTILE_CONT(0.5) WITHIN GROUP (
                    ORDER BY active_seconds
                ) FILTER (WHERE is_meaningful), 0)::bigint AS median_seconds
            FROM metric_user_sessions
            WHERE session_date BETWEEN :from AND :to
        """.trimIndent()

        private val TOP_STUDIOS_SQL = """
            SELECT
                ms.studio_id,
                COALESCE(s.name, 'Nieznane studio') AS studio_name,
                COALESCE(SUM(ms.active_seconds), 0) AS active_seconds,
                COALESCE(SUM(ms.active_seconds) FILTER (WHERE ms.actor_kind = 'OWNER'), 0)    AS owner_seconds,
                COALESCE(SUM(ms.active_seconds) FILTER (WHERE ms.actor_kind = 'EMPLOYEE'), 0) AS employee_seconds,
                COUNT(*)                        AS sessions,
                COUNT(DISTINCT ms.user_id)      AS active_users
            FROM metric_user_sessions ms
            LEFT JOIN studios s ON s.id = ms.studio_id
            WHERE ms.session_date BETWEEN :from AND :to
              AND ms.is_meaningful = true
            GROUP BY ms.studio_id, s.name
            ORDER BY active_seconds DESC
            LIMIT :limit
        """.trimIndent()
    }
}
