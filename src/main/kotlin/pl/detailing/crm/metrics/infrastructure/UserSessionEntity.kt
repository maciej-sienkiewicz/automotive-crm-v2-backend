package pl.detailing.crm.metrics.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.metrics.domain.ActorKind
import pl.detailing.crm.metrics.domain.SessionEndReason
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One row per CRM session, carrying *engaged* time rather than wall-clock time.
 *
 * The distinction is the point of this table. [activeSeconds] only ever grows by a
 * clamped delta reported by a browser that was visible and had recent user interaction;
 * [idleSeconds] absorbs the rest. A tab left open overnight therefore contributes a few
 * minutes of idle time and nothing to the number the business actually quotes.
 *
 * Postgres is the source of truth here (not Redis): the write rate is one UPDATE per
 * active user per minute, which is nothing for this workload, and losing Redis must not
 * silently zero a day of usage data.
 */
@Entity
@Table(
    name = "metric_user_sessions",
    indexes = [
        Index(name = "idx_metric_sessions_studio_date", columnList = "studio_id, session_date"),
        Index(name = "idx_metric_sessions_studio_user", columnList = "studio_id, user_id, started_at DESC"),
        // Sweeper: find still-open sessions that went quiet.
        Index(name = "idx_metric_sessions_open", columnList = "ended_at, last_activity_at"),
        Index(name = "idx_metric_sessions_session_key", columnList = "session_key")
    ]
)
class UserSessionEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    val userId: UUID,

    /**
     * Hash of the HTTP session id. Hashed, not stored raw: a leaked metrics dump must not
     * hand anybody a valid session cookie.
     */
    @Column(name = "session_key", nullable = false, length = 64)
    val sessionKey: String,

    /**
     * Owner vs employee, snapshotted at session start. Deliberately denormalised — an
     * employee promoted to owner in March must not retroactively rewrite February's split.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_kind", nullable = false, columnDefinition = "varchar(20)")
    val actorKind: ActorKind,

    /** Custom role name if the studio uses one, else "OWNER"/"EMPLOYEE". Display only. */
    @Column(name = "role_label", nullable = false, length = 100)
    val roleLabel: String,

    @Column(name = "started_at", nullable = false, columnDefinition = "timestamp with time zone")
    val startedAt: Instant,

    /** Last credited signal — heartbeat or, as a fallback, an authenticated API call. */
    @Column(name = "last_activity_at", nullable = false, columnDefinition = "timestamp with time zone")
    var lastActivityAt: Instant,

    @Column(name = "ended_at", columnDefinition = "timestamp with time zone")
    var endedAt: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", columnDefinition = "varchar(30)")
    var endReason: SessionEndReason? = null,

    /** Engaged seconds. The number the business means by "time spent in the CRM". */
    @Column(name = "active_seconds", nullable = false)
    var activeSeconds: Long = 0,

    /** Time the session existed but the user was demonstrably not working. */
    @Column(name = "idle_seconds", nullable = false)
    var idleSeconds: Long = 0,

    /** Clicks, keystrokes and route changes reported by the client. Zero ⇒ not meaningful. */
    @Column(name = "interaction_count", nullable = false)
    var interactionCount: Long = 0,

    /** Authenticated API calls made within this session — a server-side sanity check. */
    @Column(name = "request_count", nullable = false)
    var requestCount: Long = 0,

    /**
     * Set at close: `active_seconds >= min-meaningful-seconds AND interaction_count > 0`.
     * Every time-spent aggregate filters on it, so "empty sessions" are excluded once,
     * here, instead of in every query that ever touches this table.
     */
    @Column(name = "is_meaningful", nullable = false)
    var isMeaningful: Boolean = false,

    @Column(name = "session_date", nullable = false)
    val sessionDate: LocalDate,

    @Column(name = "device", length = 40)
    val device: String? = null,

    @Column(name = "app_version", length = 40)
    val appVersion: String? = null,

    @Column(name = "entry_route", length = 200)
    val entryRoute: String? = null,

    /** Where the user spent the session — feeds the "which screens matter" report. */
    @Column(name = "last_route", length = 200)
    var lastRoute: String? = null
) {
    val isOpen: Boolean get() = endedAt == null
}
