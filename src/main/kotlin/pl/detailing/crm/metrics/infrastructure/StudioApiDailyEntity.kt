package pl.detailing.crm.metrics.infrastructure

import jakarta.persistence.*
import java.time.LocalDate
import java.util.UUID

/**
 * Per-tenant, per-day API traffic and latency.
 *
 * Separate from [ApiEndpointDailyEntity] because the two answer different questions and
 * a single table could answer neither well: endpoint×day×studio would multiply into
 * millions of rows for an aggregate nobody queries that way.
 *
 * The reason this exists at all is a specific recurring support conversation. When a
 * studio says "u nas system działa wolno", the only honest answers are "we measured it"
 * or "we have no idea" — a platform-wide p95 cannot distinguish one customer on a bad
 * connection from a real regression, because their traffic is a rounding error in the
 * global histogram. This table makes the claim checkable per customer.
 *
 * The grain includes `module` (the vertical slice the endpoint lives in) because the same
 * rows then answer a second, commercially sharper question: which modules does this tenant
 * actually use? A customer paying for an add-on whose module shows zero calls is both a
 * churn signal and a retention conversation. Rolling up to (studio × day) alone would make
 * that answer unreachable, and rolling down to (studio × endpoint × day) would multiply the
 * table by two orders of magnitude to answer nothing extra.
 */
@Entity
@Table(
    name = "metric_studio_api_daily",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_metric_studio_api_daily",
            columnNames = ["studio_id", "usage_date", "module"]
        )
    ],
    indexes = [
        Index(name = "idx_metric_studio_api_daily_date", columnList = "usage_date")
    ]
)
class StudioApiDailyEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "usage_date", nullable = false)
    val usageDate: LocalDate,

    /** Vertical slice, e.g. `visit`, `finance`, `instagram`. Derived from the package. */
    @Column(name = "module", nullable = false, length = 60)
    val module: String,

    @Column(name = "call_count", nullable = false)
    var callCount: Long = 0,

    @Column(name = "error_count", nullable = false)
    var errorCount: Long = 0,

    @Column(name = "total_duration_ms", nullable = false)
    var totalDurationMs: Long = 0,

    @Column(name = "max_duration_ms", nullable = false)
    var maxDurationMs: Long = 0,

    /** How many distinct endpoints the tenant touched — a rough feature-breadth signal. */
    @Column(name = "distinct_endpoints", nullable = false)
    var distinctEndpoints: Int = 0
)
