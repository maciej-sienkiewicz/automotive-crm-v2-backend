package pl.detailing.crm.metrics.query

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.NotFoundException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.math.ceil

/**
 * Everything about one tenant on one screen: usage, who uses it, what they have activated,
 * and how fast they are burning through consumables.
 *
 * This is the view opened before a renewal call, an upsell call or a churn-save call, and
 * it is built to answer the questions those calls actually raise — not to display every
 * column the schema happens to have.
 */
@Service
class GetTenantMetricsHandler(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    fun handle(studioId: UUID, from: LocalDate, to: LocalDate): TenantMetricsResponse {
        val params = MapSqlParameterSource()
            .addValue("studioId", studioId)
            .addValue("from", java.sql.Date.valueOf(from))
            .addValue("to", java.sql.Date.valueOf(to))

        val header = jdbcTemplate.query(HEADER_SQL, params) { rs, _ ->
            Header(
                name = rs.getString("name") ?: "Studio bez nazwy",
                createdAt = rs.getTimestamp("created_at").toInstant(),
                planKey = rs.getString("plan_key") ?: "NONE",
                subscriptionStatus = rs.getString("subscription_status")
            )
        }.firstOrNull() ?: throw NotFoundException("Nie znaleziono studia $studioId")

        val daily = jdbcTemplate.query(DAILY_SQL, params) { rs, _ ->
            TenantDailyPoint(
                date = rs.getDate("snapshot_date").toLocalDate(),
                activeMinutes = rs.getLong("active_minutes_total"),
                activeMinutesOwner = rs.getLong("active_minutes_owner"),
                activeMinutesEmployee = rs.getLong("active_minutes_employee"),
                sessions = rs.getInt("sessions_count"),
                usersActive = rs.getInt("users_active"),
                reservations = rs.getLong("reservations_created"),
                visitsCompleted = rs.getLong("visits_completed"),
                smsSent = rs.getLong("sms_sent"),
                errors = rs.getLong("errors_total"),
                healthScore = rs.getInt("health_score")
            )
        }

        val latest = daily.lastOrNull()
        val seats = jdbcTemplate.query(SEATS_SQL, params) { rs, _ ->
            rs.getInt("seats_total") to rs.getInt("seats_active")
        }.firstOrNull() ?: (0 to 0)

        val credits = jdbcTemplate.query(
            "SELECT available_credits FROM sms_credit_balances WHERE studio_id = :studioId",
            params
        ) { rs, _ -> rs.getInt("available_credits") }.firstOrNull() ?: 0

        val smsInWindow = daily.sumOf { it.smsSent }
        val windowDays = (Duration.between(
            from.atStartOfDay(), to.plusDays(1).atStartOfDay()
        ).toDays()).coerceAtLeast(1)

        return TenantMetricsResponse(
            studioId = studioId.toString(),
            studioName = header.name,
            planKey = header.planKey,
            subscriptionStatus = header.subscriptionStatus,
            createdAt = header.createdAt,
            healthScore = latest?.healthScore ?: 0,
            churnRisk = jdbcTemplate.query(
                """
                SELECT churn_risk FROM metric_daily_studio_snapshots
                WHERE studio_id = :studioId ORDER BY snapshot_date DESC LIMIT 1
                """.trimIndent(),
                params
            ) { rs, _ -> rs.getString("churn_risk") }.firstOrNull() ?: "UNKNOWN",
            lastActivityAt = jdbcTemplate.query(
                """
                SELECT MAX(last_activity_at) AS last_seen FROM metric_user_sessions
                WHERE studio_id = :studioId
                """.trimIndent(),
                params
            ) { rs, _ -> rs.getTimestamp("last_seen")?.toInstant() }.firstOrNull(),
            totals = TenantTotals(
                activeHours = daily.sumOf { it.activeMinutes } / 60.0,
                activeHoursOwner = daily.sumOf { it.activeMinutesOwner } / 60.0,
                activeHoursEmployee = daily.sumOf { it.activeMinutesEmployee } / 60.0,
                sessions = daily.sumOf { it.sessions.toLong() },
                reservations = daily.sumOf { it.reservations },
                visitsCompleted = daily.sumOf { it.visitsCompleted },
                smsSent = smsInWindow,
                emailsSent = jdbcTemplate.query(
                    """
                    SELECT COALESCE(SUM(emails_sent), 0) AS n FROM metric_daily_studio_snapshots
                    WHERE studio_id = :studioId AND snapshot_date BETWEEN :from AND :to
                    """.trimIndent(),
                    params
                ) { rs, _ -> rs.getLong("n") }.firstOrNull() ?: 0,
                errors = daily.sumOf { it.errors },
                avgLatencyMs = latest?.let { avgLatency(params) } ?: 0,
                seatsTotal = seats.first,
                seatsActive = seats.second,
                smsCreditsRemaining = credits,
                smsCreditsDaysRemaining = projectCreditDepletion(credits, smsInWindow, windowDays)
            ),
            daily = daily,
            users = usersFor(params),
            activation = activationFor(studioId, header.createdAt),
            featureAdoption = adoptionFor(studioId)
        )
    }

    /**
     * Days until SMS credits hit zero at the observed burn rate.
     *
     * Null when the studio is not sending — a "0 days remaining" badge on a studio that
     * never sends an SMS is a false alarm, and a console that cries wolf gets muted.
     */
    internal fun projectCreditDepletion(credits: Int, smsInWindow: Long, windowDays: Long): Int? {
        if (smsInWindow <= 0 || credits <= 0) return null
        val perDay = smsInWindow.toDouble() / windowDays
        return ceil(credits / perDay).toInt()
    }

    private fun avgLatency(params: MapSqlParameterSource): Long =
        jdbcTemplate.query(
            """
            SELECT CASE WHEN SUM(call_count) > 0
                        THEN SUM(total_duration_ms) / SUM(call_count) ELSE 0 END AS avg_ms
            FROM metric_studio_api_daily
            WHERE studio_id = :studioId AND usage_date BETWEEN :from AND :to
            """.trimIndent(),
            params
        ) { rs, _ -> rs.getLong("avg_ms") }.firstOrNull() ?: 0

    private fun usersFor(params: MapSqlParameterSource): List<TenantUserUsage> =
        jdbcTemplate.query(USERS_SQL, params) { rs, _ ->
            val sessions = rs.getLong("sessions")
            val seconds = rs.getLong("active_seconds")
            TenantUserUsage(
                userId = rs.getString("user_id"),
                fullName = rs.getString("full_name"),
                email = rs.getString("email"),
                actorKind = rs.getString("actor_kind") ?: "EMPLOYEE",
                activeHours = seconds / 3600.0,
                sessions = sessions,
                avgSessionMinutes = if (sessions == 0L) 0 else seconds / sessions / 60,
                lastSeenAt = rs.getTimestamp("last_seen")?.toInstant()
            )
        }

    /**
     * Time-to-first-value, read from the business tables themselves so it works for every
     * studio that ever registered — including the ones that signed up long before this
     * module existed. Recomputing history is exactly what a metrics module should do when
     * the underlying facts are already stored.
     */
    private fun activationFor(studioId: UUID, createdAt: Instant): ActivationMilestones {
        val params = MapSqlParameterSource().addValue("studioId", studioId)

        val row = jdbcTemplate.query(ACTIVATION_SQL, params) { rs, _ ->
            arrayOf(
                rs.getTimestamp("first_login")?.toInstant(),
                rs.getTimestamp("first_customer")?.toInstant(),
                rs.getTimestamp("first_reservation")?.toInstant(),
                rs.getTimestamp("first_completed_visit")?.toInstant()
            )
        }.firstOrNull() ?: arrayOfNulls(4)

        val firstReservation = row[2]
        val firstCompleted = row[3]

        return ActivationMilestones(
            signedUpAt = createdAt,
            firstLoginAt = row[0],
            firstCustomerAt = row[1],
            firstReservationAt = firstReservation,
            firstVisitCompletedAt = firstCompleted,
            daysToFirstReservation = firstReservation?.let { Duration.between(createdAt, it).toDays() },
            daysToFirstVisitCompleted = firstCompleted?.let { Duration.between(createdAt, it).toDays() },
            // "Activated" means the studio ran a whole job through the product, start to
            // finish. Anything short of that is a trial that has not proven anything yet.
            fullyActivated = firstCompleted != null
        )
    }

    /**
     * Module usage measured by endpoint traffic, cross-referenced with what the studio pays
     * for. The row worth acting on is `paidFor && !adopted`.
     */
    private fun adoptionFor(studioId: UUID): List<FeatureAdoption> {
        val params = MapSqlParameterSource().addValue("studioId", studioId)

        val paidModules = jdbcTemplate.query(
            """
            SELECT LOWER(a.add_on_key) AS k
            FROM studio_subscription_plans ssp
            JOIN studio_subscription_add_ons saa ON saa.studio_subscription_plan_id = ssp.id
            JOIN subscription_add_ons a ON a.id = saa.add_on_id
            WHERE ssp.studio_id = :studioId
            """.trimIndent(),
            params
        ) { rs, _ -> rs.getString("k") }.toSet()

        return jdbcTemplate.query(ADOPTION_SQL, params) { rs, _ ->
            val module = rs.getString("module")
            val calls = rs.getLong("calls")
            FeatureAdoption(
                module = module,
                calls = calls,
                lastUsedAt = rs.getDate("last_used_date")
                    ?.toLocalDate()
                    ?.let { pl.detailing.crm.metrics.domain.MetricsClock.startOf(it) },
                paidFor = matchesPaidAddOn(module, paidModules),
                adopted = calls > 0
            )
        }
    }

    /**
     * Loose match between an add-on key (`INSTAGRAM_MONITORING`) and a source module
     * (`instagram`). Deliberately fuzzy: the two vocabularies are owned by different parts
     * of the system and forcing an exact mapping table would mean a new add-on silently
     * reports as unpaid until someone remembers to extend it. A false positive here costs
     * a mislabelled badge; a missing mapping costs a missed churn signal.
     */
    internal fun matchesPaidAddOn(module: String, paidAddOnKeys: Set<String>): Boolean =
        paidAddOnKeys.any { key ->
            key.split('_').any { part -> part.length >= 4 && module.contains(part) }
        }

    private data class Header(
        val name: String,
        val createdAt: Instant,
        val planKey: String,
        val subscriptionStatus: String
    )

    companion object {
        private val HEADER_SQL = """
            SELECT s.name, s.created_at, s.subscription_status, p.plan_key
            FROM studios s
            LEFT JOIN studio_subscription_plans ssp ON ssp.studio_id = s.id
            LEFT JOIN subscription_plans p ON p.id = ssp.plan_id
            WHERE s.id = :studioId
        """.trimIndent()

        private val DAILY_SQL = """
            SELECT snapshot_date, active_minutes_total, active_minutes_owner, active_minutes_employee,
                   sessions_count, users_active, reservations_created, visits_completed,
                   sms_sent, errors_total, health_score
            FROM metric_daily_studio_snapshots
            WHERE studio_id = :studioId AND snapshot_date BETWEEN :from AND :to
            ORDER BY snapshot_date
        """.trimIndent()

        private val SEATS_SQL = """
            SELECT
                (SELECT COUNT(*) FROM users u WHERE u.studio_id = :studioId AND u.is_active = true) AS seats_total,
                (SELECT COUNT(DISTINCT ms.user_id) FROM metric_user_sessions ms
                 WHERE ms.studio_id = :studioId AND ms.is_meaningful = true
                   AND ms.session_date BETWEEN :from AND :to) AS seats_active
        """.trimIndent()

        private val USERS_SQL = """
            SELECT
                u.id AS user_id,
                CONCAT(u.first_name, ' ', u.last_name) AS full_name,
                u.email,
                MAX(ms.actor_kind) AS actor_kind,
                COALESCE(SUM(ms.active_seconds) FILTER (WHERE ms.is_meaningful), 0) AS active_seconds,
                COUNT(ms.id) FILTER (WHERE ms.is_meaningful) AS sessions,
                MAX(ms.last_activity_at) AS last_seen
            FROM users u
            LEFT JOIN metric_user_sessions ms
                   ON ms.user_id = u.id
                  AND ms.session_date BETWEEN :from AND :to
            WHERE u.studio_id = :studioId AND u.is_active = true
            GROUP BY u.id, u.first_name, u.last_name, u.email
            ORDER BY active_seconds DESC
        """.trimIndent()

        private val ACTIVATION_SQL = """
            SELECT
                (SELECT MIN(al.created_at) FROM audit_logs al
                 WHERE al.studio_id = :studioId AND al.action = 'LOGIN_SUCCESS')       AS first_login,
                (SELECT MIN(c.created_at) FROM customers c
                 WHERE c.studio_id = :studioId)                                        AS first_customer,
                (SELECT MIN(a.created_at) FROM appointments a
                 WHERE a.studio_id = :studioId)                                        AS first_reservation,
                (SELECT MIN(v.pickup_date) FROM visits v
                 WHERE v.studio_id = :studioId AND v.deleted_at IS NULL)               AS first_completed_visit
        """.trimIndent()

        /**
         * Every module that exists in code, LEFT JOINed onto this tenant's traffic.
         *
         * Driven from the endpoint catalog rather than from the traffic table, for the same
         * reason the dead-endpoint report is: a module with zero calls is the interesting
         * row, and a traffic-driven query cannot produce it. The module name comes from the
         * package, so a new feature slice appears here the day it ships — no registry to
         * remember to update, which is exactly the maintenance step that gets skipped and
         * quietly makes a report wrong.
         */
        private val ADOPTION_SQL = """
            SELECT m.module,
                   COALESCE(SUM(sad.call_count), 0) AS calls,
                   MAX(sad.usage_date) AS last_used_date
            FROM (SELECT DISTINCT module FROM metric_api_endpoints WHERE is_active_in_code = true) m
            LEFT JOIN metric_studio_api_daily sad
                   ON sad.module = m.module AND sad.studio_id = :studioId
            GROUP BY m.module
            ORDER BY calls DESC
        """.trimIndent()
    }
}
