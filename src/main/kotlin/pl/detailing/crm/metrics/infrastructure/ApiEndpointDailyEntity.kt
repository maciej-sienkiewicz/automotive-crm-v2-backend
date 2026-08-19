package pl.detailing.crm.metrics.infrastructure

import jakarta.persistence.*
import java.time.LocalDate
import java.util.UUID

/**
 * Per-endpoint, per-day traffic. Written by a scheduled flush of in-memory counters,
 * never one row per request: at a few hundred requests per second, per-request rows
 * would outgrow every business table in the database within a week and buy nothing —
 * nobody has ever asked "how many times was this endpoint called at 14:37:22".
 */
@Entity
@Table(
    name = "metric_api_endpoint_daily",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_metric_api_daily", columnNames = ["endpoint_id", "usage_date"])
    ],
    indexes = [
        Index(name = "idx_metric_api_daily_date", columnList = "usage_date"),
        Index(name = "idx_metric_api_daily_endpoint", columnList = "endpoint_id, usage_date DESC")
    ]
)
class ApiEndpointDailyEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "endpoint_id", nullable = false, columnDefinition = "uuid")
    val endpointId: UUID,

    @Column(name = "usage_date", nullable = false)
    val usageDate: LocalDate,

    @Column(name = "call_count", nullable = false)
    var callCount: Long = 0,

    @Column(name = "error_count", nullable = false)
    var errorCount: Long = 0,

    /** Sum of durations; divided by [callCount] at read time to get the mean. */
    @Column(name = "total_duration_ms", nullable = false)
    var totalDurationMs: Long = 0,

    @Column(name = "max_duration_ms", nullable = false)
    var maxDurationMs: Long = 0,

    /**
     * How many distinct tenants used the endpoint that day. Separates "one studio's
     * integration script" from "a feature the whole customer base relies on" — the two
     * look identical in a raw call count.
     */
    @Column(name = "distinct_studios", nullable = false)
    var distinctStudios: Int = 0
)
