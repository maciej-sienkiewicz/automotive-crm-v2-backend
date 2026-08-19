package pl.detailing.crm.metrics.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The read model. One row per tenant per day, written by the nightly roll-up.
 *
 * Every console screen reads this table and only this table. Dashboards that aggregate
 * raw event streams on each page load get slower every month until somebody is paid to
 * rewrite them; a snapshot answers "usage per studio for the last 12 months" with a
 * 365-row index scan and stays that fast in year three.
 *
 * The row is also a historical record: it stores the plan and subscription status *as
 * they were that day*, so a studio that upgrades in June does not rewrite its own
 * January history the way a live JOIN against `studio_subscription_plans` would.
 */
@Entity
@Table(
    name = "metric_daily_studio_snapshots",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_metric_studio_snapshot", columnNames = ["studio_id", "snapshot_date"])
    ],
    indexes = [
        Index(name = "idx_metric_studio_snapshot_date", columnList = "snapshot_date"),
        Index(name = "idx_metric_studio_snapshot_studio", columnList = "studio_id, snapshot_date DESC"),
        Index(name = "idx_metric_studio_snapshot_plan", columnList = "snapshot_date, plan_key")
    ]
)
class StudioDailySnapshotEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "snapshot_date", nullable = false)
    val snapshotDate: LocalDate,

    // ── Commercial state, as it was on that day ──────────────────────────────
    @Column(name = "plan_key", nullable = false, length = 30)
    var planKey: String,

    @Column(name = "subscription_status", nullable = false, length = 20)
    var subscriptionStatus: String,

    @Column(name = "active_add_ons", nullable = false)
    var activeAddOns: Int = 0,

    /** Recognised monthly revenue in gross cents: plan + add-ons. Feeds MRR. */
    @Column(name = "mrr_gross_cents", nullable = false)
    var mrrGrossCents: Long = 0,

    // ── Usage ────────────────────────────────────────────────────────────────
    @Column(name = "users_total", nullable = false)
    var usersTotal: Int = 0,

    /** Users with at least one meaningful session that day — the seat-utilisation numerator. */
    @Column(name = "users_active", nullable = false)
    var usersActive: Int = 0,

    @Column(name = "sessions_count", nullable = false)
    var sessionsCount: Int = 0,

    @Column(name = "active_minutes_total", nullable = false)
    var activeMinutesTotal: Long = 0,

    @Column(name = "active_minutes_owner", nullable = false)
    var activeMinutesOwner: Long = 0,

    @Column(name = "active_minutes_employee", nullable = false)
    var activeMinutesEmployee: Long = 0,

    @Column(name = "api_calls", nullable = false)
    var apiCalls: Long = 0,

    // ── Business output ──────────────────────────────────────────────────────
    @Column(name = "reservations_created", nullable = false)
    var reservationsCreated: Long = 0,

    @Column(name = "visits_created", nullable = false)
    var visitsCreated: Long = 0,

    @Column(name = "visits_completed", nullable = false)
    var visitsCompleted: Long = 0,

    @Column(name = "logins", nullable = false)
    var logins: Long = 0,

    // ── Resource consumption ─────────────────────────────────────────────────
    @Column(name = "sms_sent", nullable = false)
    var smsSent: Long = 0,

    @Column(name = "emails_sent", nullable = false)
    var emailsSent: Long = 0,

    @Column(name = "sms_credits_remaining", nullable = false)
    var smsCreditsRemaining: Int = 0,

    // ── Technical quality, per tenant ────────────────────────────────────────
    @Column(name = "errors_total", nullable = false)
    var errorsTotal: Long = 0,

    @Column(name = "errors_critical", nullable = false)
    var errorsCritical: Long = 0,

    /** Mean API latency this tenant experienced. Makes "u nas wolno działa" verifiable. */
    @Column(name = "avg_latency_ms", nullable = false)
    var avgLatencyMs: Long = 0,

    // ── Derived health ───────────────────────────────────────────────────────
    /** 0–100, see `TenantHealthCalculator`. Recomputed nightly, never on the fly. */
    @Column(name = "health_score", nullable = false)
    var healthScore: Int = 0,

    @Column(name = "churn_risk", nullable = false, length = 20)
    var churnRisk: String = "HEALTHY",

    @Column(name = "last_activity_at", columnDefinition = "timestamp with time zone")
    var lastActivityAt: Instant? = null,

    @Column(name = "computed_at", nullable = false, columnDefinition = "timestamp with time zone")
    var computedAt: Instant = Instant.now()
)

/**
 * Platform-wide totals per day — the operator's own P&L and health view.
 * Cheap to compute (it is an aggregate of the studio snapshots plus a few live counts)
 * and it means the front page of the console is a single-row lookup.
 */
@Entity
@Table(name = "metric_daily_platform_snapshots")
class PlatformDailySnapshotEntity(

    @Id
    @Column(name = "snapshot_date", nullable = false)
    val snapshotDate: LocalDate,

    // ── Tenants ──────────────────────────────────────────────────────────────
    @Column(name = "studios_total", nullable = false)
    var studiosTotal: Int = 0,

    @Column(name = "studios_paying", nullable = false)
    var studiosPaying: Int = 0,

    @Column(name = "studios_trialing", nullable = false)
    var studiosTrialing: Int = 0,

    @Column(name = "studios_expired", nullable = false)
    var studiosExpired: Int = 0,

    @Column(name = "studios_plan_basic", nullable = false)
    var studiosPlanBasic: Int = 0,

    @Column(name = "studios_plan_full", nullable = false)
    var studiosPlanFull: Int = 0,

    @Column(name = "new_signups", nullable = false)
    var newSignups: Int = 0,

    @Column(name = "churned", nullable = false)
    var churned: Int = 0,

    // ── Revenue ──────────────────────────────────────────────────────────────
    @Column(name = "mrr_gross_cents", nullable = false)
    var mrrGrossCents: Long = 0,

    /** Average revenue per paying account, gross cents. */
    @Column(name = "arpa_gross_cents", nullable = false)
    var arpaGrossCents: Long = 0,

    // ── Engagement ───────────────────────────────────────────────────────────
    /** Distinct studios with a meaningful session that day / in 7 / in 30 days. */
    @Column(name = "dau_studios", nullable = false)
    var dauStudios: Int = 0,

    @Column(name = "wau_studios", nullable = false)
    var wauStudios: Int = 0,

    @Column(name = "mau_studios", nullable = false)
    var mauStudios: Int = 0,

    @Column(name = "dau_users", nullable = false)
    var dauUsers: Int = 0,

    /** DAU/MAU ×1000 (integer to avoid a float column). 200 = 20% stickiness. */
    @Column(name = "stickiness_permille", nullable = false)
    var stickinessPermille: Int = 0,

    @Column(name = "active_minutes_total", nullable = false)
    var activeMinutesTotal: Long = 0,

    // ── Output & consumption ─────────────────────────────────────────────────
    @Column(name = "reservations_created", nullable = false)
    var reservationsCreated: Long = 0,

    @Column(name = "visits_completed", nullable = false)
    var visitsCompleted: Long = 0,

    @Column(name = "sms_sent", nullable = false)
    var smsSent: Long = 0,

    @Column(name = "emails_sent", nullable = false)
    var emailsSent: Long = 0,

    // ── Technical ────────────────────────────────────────────────────────────
    @Column(name = "api_calls", nullable = false)
    var apiCalls: Long = 0,

    @Column(name = "errors_total", nullable = false)
    var errorsTotal: Long = 0,

    @Column(name = "error_groups_new", nullable = false)
    var errorGroupsNew: Int = 0,

    /** Studios that hit at least one error — the blast radius of a bad deploy. */
    @Column(name = "studios_with_errors", nullable = false)
    var studiosWithErrors: Int = 0,

    @Column(name = "computed_at", nullable = false, columnDefinition = "timestamp with time zone")
    var computedAt: Instant = Instant.now()
)
