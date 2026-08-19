package pl.detailing.crm.metrics.apiaudit

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.metrics.config.MetricsProperties
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Writes the accumulated traffic counters to Postgres.
 *
 * Uses `INSERT ... ON CONFLICT DO UPDATE` rather than read-modify-write through JPA for
 * one reason that matters operationally: the counters are *additive across app instances*.
 * Two backend containers flushing the same (endpoint, day) key at the same moment must sum
 * their counts, and a read-modify-write would silently let one overwrite the other — which
 * is not a hypothetical, it is what happens on every rolling deploy.
 *
 * The distinct-studio column is the one aggregate that cannot be summed this way (the sets
 * may overlap), so it takes the maximum — an intentional, documented under-count in the
 * multi-instance case rather than a wrong number that looks precise.
 */
@Component
class ApiUsageFlushJob(
    private val buffer: ApiUsageBuffer,
    private val jdbcTemplate: JdbcTemplate,
    private val properties: MetricsProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${crm.metrics.api-audit.flush-interval-ms:60000}",
        initialDelay = 30_000
    )
    @Transactional
    fun flush() {
        if (!properties.enabled) return

        val drained = buffer.drain()
        if (drained.isEmpty()) return

        try {
            drained.forEach { (key, stats) ->
                val calls = stats.calls.sum()
                if (calls == 0L) return@forEach

                jdbcTemplate.update(
                    """
                    INSERT INTO metric_api_endpoint_daily
                        (id, endpoint_id, usage_date, call_count, error_count,
                         total_duration_ms, max_duration_ms, distinct_studios)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (endpoint_id, usage_date) DO UPDATE SET
                        call_count        = metric_api_endpoint_daily.call_count + EXCLUDED.call_count,
                        error_count       = metric_api_endpoint_daily.error_count + EXCLUDED.error_count,
                        total_duration_ms = metric_api_endpoint_daily.total_duration_ms + EXCLUDED.total_duration_ms,
                        max_duration_ms   = GREATEST(metric_api_endpoint_daily.max_duration_ms, EXCLUDED.max_duration_ms),
                        distinct_studios  = GREATEST(metric_api_endpoint_daily.distinct_studios, EXCLUDED.distinct_studios)
                    """.trimIndent(),
                    UUID.randomUUID(),
                    key.endpointId,
                    java.sql.Date.valueOf(key.date),
                    calls,
                    stats.errors.sum(),
                    stats.totalDurationMs.sum(),
                    stats.maxDurationMs,
                    stats.studios.size
                )

                // The catalog's own "alive?" columns. GREATEST guards against an
                // out-of-order flush pushing last_called_at backwards.
                jdbcTemplate.update(
                    """
                    UPDATE metric_api_endpoints
                    SET total_calls    = total_calls + ?,
                        last_called_at = GREATEST(COALESCE(last_called_at, ?), ?)
                    WHERE id = ?
                    """.trimIndent(),
                    calls,
                    Timestamp.from(Instant.EPOCH),
                    Timestamp.from(Instant.now()),
                    key.endpointId
                )
            }

            flushStudioAggregate()

            log.debug("Zapisano ruch API dla {} kluczy (endpoint × dzień)", drained.size)
        } catch (ex: Exception) {
            log.error("Zapis liczników ruchu API nie powiódł się: {}", ex.message, ex)
        }
    }

    /**
     * Per-tenant traffic and latency. Same additive upsert for the same reason: two
     * instances must sum, not overwrite.
     */
    private fun flushStudioAggregate() {
        val drained = buffer.drainStudios()
        if (drained.isEmpty()) return

        drained.forEach { (key, stats) ->
            val calls = stats.calls.sum()
            if (calls == 0L) return@forEach

            jdbcTemplate.update(
                """
                INSERT INTO metric_studio_api_daily
                    (id, studio_id, usage_date, module, call_count, error_count,
                     total_duration_ms, max_duration_ms, distinct_endpoints)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (studio_id, usage_date, module) DO UPDATE SET
                    call_count         = metric_studio_api_daily.call_count + EXCLUDED.call_count,
                    error_count        = metric_studio_api_daily.error_count + EXCLUDED.error_count,
                    total_duration_ms  = metric_studio_api_daily.total_duration_ms + EXCLUDED.total_duration_ms,
                    max_duration_ms    = GREATEST(metric_studio_api_daily.max_duration_ms, EXCLUDED.max_duration_ms),
                    distinct_endpoints = GREATEST(metric_studio_api_daily.distinct_endpoints, EXCLUDED.distinct_endpoints)
                """.trimIndent(),
                UUID.randomUUID(),
                key.studioId,
                java.sql.Date.valueOf(key.date),
                key.module,
                calls,
                stats.errors.sum(),
                stats.totalDurationMs.sum(),
                stats.maxDurationMs,
                stats.endpoints.size
            )
        }
    }

    @PreDestroy
    fun flushOnShutdown() {
        flush()
    }
}
