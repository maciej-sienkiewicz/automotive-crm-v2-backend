package pl.detailing.crm.metrics.query

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.metrics.domain.ErrorGroupStatus
import pl.detailing.crm.metrics.infrastructure.ErrorGroupRepository
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.ValidationException
import java.time.Instant

/**
 * Error console: groups first, occurrences on demand, and always with the answer to
 * "whose customer did this break".
 *
 * The listing joins each group to its impacted tenants **by name**, because that is the
 * form the question always takes in practice — an operator opening this screen after a
 * deploy needs to know whether to call three studios or none, and a list of UUIDs does
 * not answer that.
 */
@Service
class GetErrorAnalyticsHandler(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val errorGroups: ErrorGroupRepository
) {

    fun listGroups(status: String?, limit: Int = 100): List<ErrorGroupRow> {
        val parsedStatus = status?.let {
            runCatching { ErrorGroupStatus.valueOf(it.uppercase()) }
                .getOrElse { throw ValidationException("Nieznany status grupy błędów: $status") }
        }

        return jdbcTemplate.query(
            GROUPS_SQL,
            MapSqlParameterSource()
                .addValue("status", parsedStatus?.name)
                .addValue("limit", limit.coerceIn(1, 500))
        ) { rs, _ ->
            val fingerprint = rs.getString("fingerprint")
            ErrorGroupRow(
                fingerprint = fingerprint,
                title = rs.getString("title"),
                origin = rs.getString("origin"),
                severity = rs.getString("severity"),
                status = rs.getString("status"),
                occurrences = rs.getLong("occurrence_count"),
                affectedStudios = rs.getInt("affected_studios"),
                firstSeenAt = rs.getTimestamp("first_seen_at").toInstant(),
                lastSeenAt = rs.getTimestamp("last_seen_at").toInstant(),
                topStudios = topStudios(fingerprint)
            )
        }
    }

    fun groupDetail(fingerprint: String, occurrenceLimit: Int = 20): ErrorGroupDetail {
        val group = listGroups(null, 500).firstOrNull { it.fingerprint == fingerprint }
            ?: throw NotFoundException("Nie znaleziono grupy błędów $fingerprint")

        val occurrences = jdbcTemplate.query(
            """
            SELECT e.id, e.studio_id, s.name AS studio_name, e.user_id, e.occurred_at,
                   e.message, e.path, e.http_status, e.correlation_id, e.app_version, e.stack_trace
            FROM metric_error_events e
            LEFT JOIN studios s ON s.id = e.studio_id
            WHERE e.fingerprint = :fingerprint
            ORDER BY e.occurred_at DESC
            LIMIT :limit
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("fingerprint", fingerprint)
                .addValue("limit", occurrenceLimit.coerceIn(1, 100))
        ) { rs, _ ->
            ErrorOccurrence(
                id = rs.getString("id"),
                studioId = rs.getString("studio_id"),
                studioName = rs.getString("studio_name"),
                userId = rs.getString("user_id"),
                occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                message = rs.getString("message"),
                path = rs.getString("path"),
                httpStatus = rs.getObject("http_status") as? Int,
                correlationId = rs.getString("correlation_id"),
                appVersion = rs.getString("app_version"),
                stackTrace = rs.getString("stack_trace")
            )
        }

        return ErrorGroupDetail(group = group, recentOccurrences = occurrences)
    }

    /** Every error a given tenant hit — the view support opens with a customer on the line. */
    fun errorsForStudio(studioId: java.util.UUID, limit: Int = 50): List<ErrorGroupRow> =
        jdbcTemplate.query(
            """
            SELECT g.fingerprint, g.title, g.origin, g.severity, g.status,
                   i.occurrences AS occurrence_count, g.affected_studios,
                   i.first_seen_at, i.last_seen_at
            FROM metric_error_group_impacts i
            JOIN metric_error_groups g ON g.fingerprint = i.fingerprint
            WHERE i.studio_id = :studioId
            ORDER BY i.last_seen_at DESC
            LIMIT :limit
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("studioId", studioId)
                .addValue("limit", limit.coerceIn(1, 200))
        ) { rs, _ ->
            ErrorGroupRow(
                fingerprint = rs.getString("fingerprint"),
                title = rs.getString("title"),
                origin = rs.getString("origin"),
                severity = rs.getString("severity"),
                status = rs.getString("status"),
                // Occurrences for *this* tenant, not globally — the number that matters
                // when the question is "how bad was it for them".
                occurrences = rs.getLong("occurrence_count"),
                affectedStudios = rs.getInt("affected_studios"),
                firstSeenAt = rs.getTimestamp("first_seen_at").toInstant(),
                lastSeenAt = rs.getTimestamp("last_seen_at").toInstant(),
                topStudios = emptyList()
            )
        }

    @Transactional
    fun updateStatus(fingerprint: String, request: UpdateErrorGroupRequest): ErrorGroupRow {
        val group = errorGroups.findById(fingerprint).orElseThrow {
            NotFoundException("Nie znaleziono grupy błędów $fingerprint")
        }

        val status = runCatching { ErrorGroupStatus.valueOf(request.status.uppercase()) }
            .getOrElse { throw ValidationException("Nieznany status: ${request.status}") }

        group.status = status
        group.resolutionNote = request.note?.take(1000)
        group.resolvedInVersion = request.resolvedInVersion?.take(40)
        // Resolving stamps the time; reopening clears it, so "resolved but still firing"
        // can never be represented — the recorder reopens the group on a new occurrence.
        group.resolvedAt = if (status == ErrorGroupStatus.RESOLVED) Instant.now() else null
        errorGroups.save(group)

        return listGroups(null, 500).first { it.fingerprint == fingerprint }
    }

    private fun topStudios(fingerprint: String): List<AffectedStudio> =
        jdbcTemplate.query(
            """
            SELECT i.studio_id, COALESCE(s.name, 'Nieznane studio') AS studio_name,
                   i.occurrences, i.first_seen_at, i.last_seen_at
            FROM metric_error_group_impacts i
            LEFT JOIN studios s ON s.id = i.studio_id
            WHERE i.fingerprint = :fingerprint
            ORDER BY i.occurrences DESC
            LIMIT 10
            """.trimIndent(),
            MapSqlParameterSource().addValue("fingerprint", fingerprint)
        ) { rs, _ ->
            AffectedStudio(
                studioId = rs.getString("studio_id"),
                studioName = rs.getString("studio_name"),
                occurrences = rs.getLong("occurrences"),
                firstSeenAt = rs.getTimestamp("first_seen_at").toInstant(),
                lastSeenAt = rs.getTimestamp("last_seen_at").toInstant()
            )
        }

    companion object {
        private val GROUPS_SQL = """
            SELECT fingerprint, title, origin, severity, status,
                   occurrence_count, affected_studios, first_seen_at, last_seen_at
            FROM metric_error_groups
            WHERE (:status IS NULL OR status = :status)
            ORDER BY
                -- Blast radius before volume: a defect hitting twelve studios once each is
                -- a bigger problem than one hitting a single studio four hundred times.
                affected_studios DESC,
                last_seen_at DESC
            LIMIT :limit
        """.trimIndent()
    }
}
