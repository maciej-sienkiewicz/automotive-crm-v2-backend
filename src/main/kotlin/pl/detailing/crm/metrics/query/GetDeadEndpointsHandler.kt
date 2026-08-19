package pl.detailing.crm.metrics.query

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import pl.detailing.crm.metrics.config.MetricsProperties
import pl.detailing.crm.metrics.domain.EndpointVitality
import pl.detailing.crm.metrics.domain.MetricsClock
import pl.detailing.crm.metrics.infrastructure.ApiEndpointRepository
import java.time.Duration
import java.time.Instant

/**
 * Answers "which endpoints can we delete?" with evidence rather than intuition.
 *
 * ## The two guards that make this report trustworthy
 *
 * 1. **The catalog, not the traffic, is the driver.** Endpoints are enumerated from
 *    Spring's routing table at boot, so one that has never been called still appears —
 *    and those are precisely the ones worth deleting. A traffic-derived report structurally
 *    cannot name them.
 *
 * 2. **Observation window honesty.** Nothing is called dead until the audit has been
 *    running for `min-observation-days`. A monthly settlement endpoint looks identical to
 *    a dead one after three days of data, and deleting it is a production incident that
 *    would be blamed — correctly — on this report.
 *
 * The output carries a plain-language recommendation for each row, so the reader is not
 * re-deriving the classification rule from a timestamp and a threshold.
 */
@Service
class GetDeadEndpointsHandler(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val endpointRepository: ApiEndpointRepository,
    private val properties: MetricsProperties
) {

    fun handle(includeInactiveInCode: Boolean = false): DeadEndpointReport {
        val config = properties.apiAudit
        val now = Instant.now()

        val observationStart = endpointRepository.findObservationStart()
        val observationDays = observationStart
            ?.let { Duration.between(it, now).toDays() }
            ?: 0
        val reliable = observationDays >= config.minObservationDays

        val rows = jdbcTemplate.query(
            USAGE_SQL,
            MapSqlParameterSource()
                .addValue("since", java.sql.Date.valueOf(MetricsClock.today().minusDays(30)))
                .addValue("includeInactive", includeInactiveInCode)
        ) { rs, _ ->
            val lastCalled = rs.getTimestamp("last_called_at")?.toInstant()
            val daysSince = lastCalled?.let { Duration.between(it, now).toDays() }
            val calls30d = rs.getLong("calls_30d")
            val callsTotal = rs.getLong("total_calls")
            val errors30d = rs.getLong("errors_30d")
            val exempt = rs.getBoolean("is_retention_exempt")

            val vitality = classify(
                reliable = reliable,
                lastCalled = lastCalled,
                daysSinceLastCall = daysSince,
                calls30d = calls30d,
                callsTotal = callsTotal
            )

            EndpointUsageRow(
                method = rs.getString("http_method"),
                path = rs.getString("path_template"),
                controller = rs.getString("controller"),
                handler = rs.getString("handler"),
                module = rs.getString("module"),
                vitality = vitality,
                vitalityLabel = vitality.label,
                lastCalledAt = lastCalled,
                daysSinceLastCall = daysSince,
                totalCalls = callsTotal,
                calls30d = calls30d,
                distinctStudios30d = rs.getInt("studios_30d"),
                avgDurationMs = if (calls30d > 0) rs.getLong("duration_30d") / calls30d else 0,
                errorRate30dPercent = if (calls30d > 0) errors30d * 100.0 / calls30d else 0.0,
                requiresAuth = rs.getBoolean("requires_auth"),
                retentionExempt = exempt,
                recommendation = recommend(vitality, exempt, rs.getInt("studios_30d"), observationDays)
            )
        }

        return DeadEndpointReport(
            observationStartedAt = observationStart,
            observationDays = observationDays,
            reliable = reliable,
            totalEndpoints = rows.size,
            summary = rows.groupingBy { it.vitality.name }.eachCount(),
            // Deletion candidates first: the report is read top-down and its whole purpose
            // is the first screen. Within a class, the least-used endpoint leads.
            endpoints = rows.sortedWith(
                compareBy<EndpointUsageRow> { -it.vitality.ordinal }.thenBy { it.calls30d }
            )
        )
    }

    internal fun classify(
        reliable: Boolean,
        lastCalled: Instant?,
        daysSinceLastCall: Long?,
        calls30d: Long,
        callsTotal: Long
    ): EndpointVitality {
        val config = properties.apiAudit

        if (!reliable) return EndpointVitality.INSUFFICIENT_DATA

        // Never called at all is a stronger signal than "went quiet", and deserves its own
        // class: it is the difference between "probably safe to delete" and "certainly".
        if (lastCalled == null || callsTotal == 0L) return EndpointVitality.NEVER_CALLED

        val days = daysSinceLastCall ?: return EndpointVitality.NEVER_CALLED

        return when {
            days >= config.deadAfterDays -> EndpointVitality.DEAD
            days >= config.dormantAfterDays -> EndpointVitality.DORMANT
            calls30d < config.lowTrafficThreshold -> EndpointVitality.LOW_TRAFFIC
            else -> EndpointVitality.ACTIVE
        }
    }

    private fun recommend(
        vitality: EndpointVitality,
        exempt: Boolean,
        studios30d: Int,
        observationDays: Long
    ): String = when {
        exempt -> "Oznaczony jako wyłączony z czyszczenia — pozostawić."

        vitality == EndpointVitality.INSUFFICIENT_DATA ->
            "Za wcześnie na ocenę: audyt zbiera dane od $observationDays dni. " +
                "Potrzeba co najmniej ${properties.apiAudit.minObservationDays}."

        vitality == EndpointVitality.NEVER_CALLED ->
            "Nigdy nie wywołany od startu audytu — najsilniejszy kandydat do usunięcia. " +
                "Przed usunięciem sprawdź, czy nie jest wywoływany rocznie lub przez integrację zewnętrzną."

        vitality == EndpointVitality.DEAD ->
            "Brak ruchu od ponad ${properties.apiAudit.deadAfterDays} dni — kandydat do usunięcia."

        vitality == EndpointVitality.DORMANT ->
            "Brak ruchu od ponad ${properties.apiAudit.dormantAfterDays} dni — sprawdzić, czy funkcja jest jeszcze w UI."

        vitality == EndpointVitality.LOW_TRAFFIC && studios30d <= 1 ->
            "Używany przez co najwyżej jedno studio — zweryfikować, czy to nie pozostałość po jednym wdrożeniu."

        vitality == EndpointVitality.LOW_TRAFFIC ->
            "Niski ruch, ale realni użytkownicy — zostawić, obserwować."

        else -> "Aktywnie używany."
    }

    companion object {
        /**
         * LEFT JOIN from the catalog, so endpoints with no traffic rows survive the join
         * and appear in the output with zeroes. An INNER JOIN here would silently drop
         * every endpoint the report exists to find — the single most likely way to write
         * this query wrong.
         */
        private val USAGE_SQL = """
            SELECT
                e.http_method, e.path_template, e.controller, e.handler, e.module,
                e.last_called_at, e.total_calls, e.requires_auth, e.is_retention_exempt,
                COALESCE(SUM(d.call_count), 0)        AS calls_30d,
                COALESCE(SUM(d.error_count), 0)       AS errors_30d,
                COALESCE(SUM(d.total_duration_ms), 0) AS duration_30d,
                COALESCE(MAX(d.distinct_studios), 0)  AS studios_30d
            FROM metric_api_endpoints e
            LEFT JOIN metric_api_endpoint_daily d
                   ON d.endpoint_id = e.id AND d.usage_date >= :since
            WHERE (e.is_active_in_code = true OR :includeInactive = true)
            GROUP BY e.id, e.http_method, e.path_template, e.controller, e.handler,
                     e.module, e.last_called_at, e.total_calls, e.requires_auth, e.is_retention_exempt
        """.trimIndent()
    }
}
