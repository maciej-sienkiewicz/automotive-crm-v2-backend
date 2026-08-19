package pl.detailing.crm.metrics.infrastructure

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pl.detailing.crm.metrics.domain.ActorKind
import pl.detailing.crm.metrics.domain.MetricEventType
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Append-only stream of business-meaningful events.
 *
 * Grain: one row per event. This table is written to on the hot path (asynchronously,
 * see [pl.detailing.crm.metrics.ingest.MetricEventRecorder]) and read almost exclusively
 * by the nightly roll-up — dashboards read the snapshot tables, never this one. That is
 * the whole reason it can stay this simple: it is a ledger, not a query surface.
 *
 * Enum columns declare an explicit `varchar` [Column.columnDefinition] so Hibernate does
 * not generate a `CHECK (col IN (...))` constraint that would turn every new enum
 * constant into an insert failure — the same trap `V36__fix_all_enum_check_constraints`
 * was written to clean up.
 */
@Entity
@Table(
    name = "metric_events",
    indexes = [
        // Roll-up scan: everything for one studio on one day.
        Index(name = "idx_metric_events_studio_date_type", columnList = "studio_id, event_date, event_type"),
        // Platform-wide daily aggregate across all tenants.
        Index(name = "idx_metric_events_date_type", columnList = "event_date, event_type"),
        // Per-user drill-down ("who in this studio actually books visits").
        Index(name = "idx_metric_events_studio_user", columnList = "studio_id, user_id, occurred_at DESC"),
        // Retention purge walks this.
        Index(name = "idx_metric_events_occurred_at", columnList = "occurred_at")
    ]
)
class MetricEventEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    /**
     * Owning tenant. Nullable only for platform-level events that genuinely precede a
     * tenant (e.g. a failed login against an unknown e-mail).
     */
    @Column(name = "studio_id", columnDefinition = "uuid")
    val studioId: UUID?,

    @Column(name = "user_id", columnDefinition = "uuid")
    val userId: UUID?,

    /** Snapshot of who acted, so owner-vs-employee splits survive later role changes. */
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_kind", columnDefinition = "varchar(20)")
    val actorKind: ActorKind?,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, columnDefinition = "varchar(60)")
    val eventType: MetricEventType,

    /**
     * How many units the event carries — SMS segments, uploaded bytes, tokens spent.
     * Defaults to 1 so plain "it happened" events need no special handling in SQL:
     * `SUM(quantity)` is always the right aggregate.
     */
    @Column(name = "quantity", nullable = false)
    val quantity: Long = 1,

    @Column(name = "occurred_at", nullable = false, columnDefinition = "timestamp with time zone")
    val occurredAt: Instant,

    /**
     * Calendar day the event belongs to, in Europe/Warsaw — the timezone every studio
     * on this platform actually operates in. Materialised rather than derived in SQL so
     * the roll-up can use a plain index instead of a function scan.
     */
    @Column(name = "event_date", nullable = false)
    val eventDate: LocalDate,

    /** Free-form dimensions that are not worth their own column (module, channel, source). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    val payload: String? = null
)
