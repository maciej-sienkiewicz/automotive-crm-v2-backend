package pl.detailing.crm.metrics.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Catalog of every HTTP endpoint the application exposes.
 *
 * **Why a catalog and not just traffic counters:** a truly dead endpoint emits no
 * traffic, so a report built purely from observed requests can never name it — the
 * endpoints you most want to delete are exactly the ones missing from that report.
 * The catalog is therefore seeded at every boot from Spring's `RequestMappingHandlerMapping`
 * (see [pl.detailing.crm.metrics.apiaudit.EndpointCatalogRegistrar]), so the dead-endpoint
 * query is a LEFT JOIN from "what exists" onto "what was called", not the other way round.
 *
 * [isActiveInCode] closes the loop in the other direction: a row whose endpoint has been
 * deleted from the source is marked stale at the next boot instead of lingering forever.
 */
@Entity
@Table(
    name = "metric_api_endpoints",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_metric_api_endpoints_signature", columnNames = ["http_method", "path_template"])
    ],
    indexes = [
        Index(name = "idx_metric_api_endpoints_last_called", columnList = "last_called_at"),
        Index(name = "idx_metric_api_endpoints_module", columnList = "module")
    ]
)
class ApiEndpointEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "http_method", nullable = false, length = 10)
    val httpMethod: String,

    /** Spring's URI template, e.g. `/api/v1/customers/{customerId}`. Never a concrete URI. */
    @Column(name = "path_template", nullable = false, length = 300)
    val pathTemplate: String,

    @Column(name = "controller", nullable = false, length = 150)
    var controller: String,

    @Column(name = "handler", nullable = false, length = 150)
    var handler: String,

    /** Vertical slice the endpoint belongs to, derived from its package. */
    @Column(name = "module", nullable = false, length = 60)
    var module: String,

    /** False once the endpoint no longer exists in code — safe to purge from the report. */
    @Column(name = "is_active_in_code", nullable = false)
    var isActiveInCode: Boolean = true,

    /** False for endpoints on the security permit-all list (public webhooks, health). */
    @Column(name = "requires_auth", nullable = false)
    var requiresAuth: Boolean = true,

    /**
     * When this endpoint entered the catalog. Also the earliest moment we could possibly
     * have observed traffic for it — the honesty guard behind INSUFFICIENT_DATA.
     */
    @Column(name = "first_seen_at", nullable = false, columnDefinition = "timestamp with time zone")
    val firstSeenAt: Instant,

    @Column(name = "last_seen_in_code_at", nullable = false, columnDefinition = "timestamp with time zone")
    var lastSeenInCodeAt: Instant,

    @Column(name = "last_called_at", columnDefinition = "timestamp with time zone")
    var lastCalledAt: Instant? = null,

    @Column(name = "total_calls", nullable = false)
    var totalCalls: Long = 0,

    /**
     * Set by hand by an operator: "yes, we know, it stays" (a quarterly export, a
     * disaster-recovery hook). Keeps the report actionable instead of accumulating
     * permanent false positives everyone learns to ignore.
     */
    @Column(name = "is_retention_exempt", nullable = false)
    var isRetentionExempt: Boolean = false,

    @Column(name = "exemption_note", length = 300)
    var exemptionNote: String? = null
)
