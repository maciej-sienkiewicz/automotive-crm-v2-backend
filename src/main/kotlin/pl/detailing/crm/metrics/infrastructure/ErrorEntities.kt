package pl.detailing.crm.metrics.infrastructure

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pl.detailing.crm.metrics.domain.ErrorGroupStatus
import pl.detailing.crm.metrics.domain.ErrorOrigin
import pl.detailing.crm.metrics.domain.ErrorSeverity
import java.time.Instant
import java.util.UUID

/**
 * A single error occurrence, backend or frontend, always carrying the tenant it hit.
 *
 * `studio_id` is nullable strictly for failures that happen before authentication
 * resolves a tenant (a login attempt, a webhook with a bad signature). Everything else
 * carries it, because "which customer did this outage touch" is the first question asked
 * in every support conversation and reconstructing it from log greps after the fact is
 * how an afternoon disappears.
 */
@Entity
@Table(
    name = "metric_error_events",
    indexes = [
        Index(name = "idx_metric_errors_studio_time", columnList = "studio_id, occurred_at DESC"),
        Index(name = "idx_metric_errors_fingerprint", columnList = "fingerprint, occurred_at DESC"),
        Index(name = "idx_metric_errors_occurred", columnList = "occurred_at"),
        Index(name = "idx_metric_errors_correlation", columnList = "correlation_id")
    ]
)
class ErrorEventEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", columnDefinition = "uuid")
    val studioId: UUID?,

    @Column(name = "user_id", columnDefinition = "uuid")
    val userId: UUID?,

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, columnDefinition = "varchar(20)")
    val origin: ErrorOrigin,

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, columnDefinition = "varchar(20)")
    val severity: ErrorSeverity,

    /** Groups occurrences of the same defect. See `ErrorFingerprinter`. */
    @Column(name = "fingerprint", nullable = false, length = 32)
    val fingerprint: String,

    @Column(name = "exception_class", nullable = false, length = 200)
    val exceptionClass: String,

    @Column(name = "message", length = 1000)
    val message: String?,

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    val stackTrace: String?,

    @Column(name = "http_method", length = 10)
    val httpMethod: String?,

    /** URI template for backend errors, frontend route for frontend errors. */
    @Column(name = "path", length = 300)
    val path: String?,

    @Column(name = "http_status")
    val httpStatus: Int?,

    /**
     * Ties a frontend error to the backend request that caused it — the CRM already
     * emits `X-Correlation-ID` on every response, so the frontend can echo it back and
     * both halves of a failure land on one timeline.
     */
    @Column(name = "correlation_id", columnDefinition = "uuid")
    val correlationId: UUID?,

    @Column(name = "occurred_at", nullable = false, columnDefinition = "timestamp with time zone")
    val occurredAt: Instant,

    @Column(name = "app_version", length = 40)
    val appVersion: String?,

    @Column(name = "user_agent", length = 300)
    val userAgent: String?,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context", columnDefinition = "jsonb")
    val context: String? = null
)

/**
 * One row per distinct defect. Turns "4 812 errors yesterday" — a number nobody can act
 * on — into "nine defects, this one hit 23 studios".
 */
@Entity
@Table(
    name = "metric_error_groups",
    indexes = [
        Index(name = "idx_metric_error_groups_last_seen", columnList = "last_seen_at DESC"),
        Index(name = "idx_metric_error_groups_status", columnList = "status, last_seen_at DESC")
    ]
)
class ErrorGroupEntity(

    @Id
    @Column(name = "fingerprint", nullable = false, length = 32)
    val fingerprint: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, columnDefinition = "varchar(20)")
    val origin: ErrorOrigin,

    @Column(name = "title", nullable = false, length = 300)
    var title: String,

    @Column(name = "exception_class", nullable = false, length = 200)
    val exceptionClass: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, columnDefinition = "varchar(20)")
    var severity: ErrorSeverity,

    @Column(name = "first_seen_at", nullable = false, columnDefinition = "timestamp with time zone")
    val firstSeenAt: Instant,

    @Column(name = "last_seen_at", nullable = false, columnDefinition = "timestamp with time zone")
    var lastSeenAt: Instant,

    @Column(name = "occurrence_count", nullable = false)
    var occurrenceCount: Long = 0,

    /** Denormalised count of distinct tenants in `metric_error_group_impacts`. */
    @Column(name = "affected_studios", nullable = false)
    var affectedStudios: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "varchar(20)")
    var status: ErrorGroupStatus = ErrorGroupStatus.NEW,

    @Column(name = "resolved_at", columnDefinition = "timestamp with time zone")
    var resolvedAt: Instant? = null,

    @Column(name = "resolution_note", length = 1000)
    var resolutionNote: String? = null,

    /**
     * Version the group was last marked resolved in. If it reappears afterwards the
     * console flags it as a regression rather than quietly reopening it.
     */
    @Column(name = "resolved_in_version", length = 40)
    var resolvedInVersion: String? = null
)

/**
 * Which tenants a given defect touched, and how hard.
 *
 * Could be derived with `GROUP BY studio_id` over the occurrence table — but that table
 * is purged after 90 days and the aggregate is asked for on every console page load.
 * A tiny upserted table keeps "which of my customers did this affect" instant and keeps
 * the answer after the raw rows are gone.
 */
@Entity
@Table(
    name = "metric_error_group_impacts",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_metric_error_impact", columnNames = ["fingerprint", "studio_id"])
    ],
    indexes = [
        Index(name = "idx_metric_error_impact_studio", columnList = "studio_id, last_seen_at DESC")
    ]
)
class ErrorGroupImpactEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "fingerprint", nullable = false, length = 32)
    val fingerprint: String,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "occurrences", nullable = false)
    var occurrences: Long = 0,

    @Column(name = "first_seen_at", nullable = false, columnDefinition = "timestamp with time zone")
    val firstSeenAt: Instant,

    @Column(name = "last_seen_at", nullable = false, columnDefinition = "timestamp with time zone")
    var lastSeenAt: Instant,

    /** Distinct users of that tenant who hit it — one annoyed owner vs the whole crew. */
    @Column(name = "affected_users", nullable = false)
    var affectedUsers: Int = 0
)
