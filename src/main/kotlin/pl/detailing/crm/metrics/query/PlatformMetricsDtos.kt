package pl.detailing.crm.metrics.query

import pl.detailing.crm.metrics.domain.EndpointVitality
import java.time.Instant
import java.time.LocalDate

/**
 * Read models for the platform-operator console.
 *
 * Everything here is a flat, presentation-ready shape. The console is the *only* consumer,
 * and deriving percentages or risk bands in the frontend would mean the same rule lives in
 * two places and drifts — a "health score" that reads 71 on one screen and 68 on another
 * is worse than no health score.
 */

// ── Front page ────────────────────────────────────────────────────────────────

data class PlatformOverviewResponse(
    val asOf: LocalDate,
    val subscriptions: SubscriptionOverview,
    val engagement: EngagementOverview,
    val activity: ActivityOverview,
    val reliability: ReliabilityOverview,
    val pipeline: PipelineHealth
)

data class SubscriptionOverview(
    val studiosTotal: Long,
    val paying: Long,
    val trialing: Long,
    val expired: Long,
    val withoutPlan: Long,
    val planBasic: Long,
    val planFull: Long,
    /** Gross cents; the console formats. Money never crosses this boundary as a float. */
    val mrrGrossCents: Long,
    val arpaGrossCents: Long,
    val addOnRevenueGrossCents: Long,
    val activeAddOns: Map<String, Long>,
    val computedAt: Instant
)

data class EngagementOverview(
    val dauStudios: Int,
    val wauStudios: Int,
    val mauStudios: Int,
    val dauUsers: Int,
    /** DAU/MAU as a percentage — the standard stickiness ratio. */
    val stickinessPercent: Double,
    val activeMinutesTotal: Long,
    val avgMinutesPerActiveStudio: Long
)

data class ActivityOverview(
    val reservationsCreated: Long,
    val visitsCompleted: Long,
    val smsSent: Long,
    val emailsSent: Long,
    val apiCalls: Long
)

data class ReliabilityOverview(
    val errorsTotal: Long,
    val newErrorGroups: Int,
    val studiosWithErrors: Int,
    val openErrorGroups: Int
)

/** Self-diagnostics: is the metrics pipeline itself healthy, or quietly dropping data? */
data class PipelineHealth(
    val eventQueueDepth: Int,
    val eventQueueCapacity: Int,
    val eventsDropped: Long,
    val apiBufferKeys: Int,
    val apiEventsDropped: Long,
    val openSessionsTracked: Int,
    val healthy: Boolean
)

// ── Tenant drill-down ─────────────────────────────────────────────────────────

data class TenantMetricsResponse(
    val studioId: String,
    val studioName: String,
    val planKey: String,
    val subscriptionStatus: String,
    val createdAt: Instant,
    val healthScore: Int,
    val churnRisk: String,
    val lastActivityAt: Instant?,
    val totals: TenantTotals,
    val daily: List<TenantDailyPoint>,
    val users: List<TenantUserUsage>,
    val activation: ActivationMilestones,
    val featureAdoption: List<FeatureAdoption>
)

data class TenantTotals(
    val activeHours: Double,
    val activeHoursOwner: Double,
    val activeHoursEmployee: Double,
    val sessions: Long,
    val reservations: Long,
    val visitsCompleted: Long,
    val smsSent: Long,
    val emailsSent: Long,
    val errors: Long,
    val avgLatencyMs: Long,
    val seatsTotal: Int,
    val seatsActive: Int,
    val smsCreditsRemaining: Int,
    /**
     * Days until SMS credits run out at the current burn rate, or null when the studio
     * is not sending. The most reliable upsell trigger the platform has: a studio about
     * to hit zero is a studio about to have automations fail silently.
     */
    val smsCreditsDaysRemaining: Int?
)

data class TenantDailyPoint(
    val date: LocalDate,
    val activeMinutes: Long,
    val activeMinutesOwner: Long,
    val activeMinutesEmployee: Long,
    val sessions: Int,
    val usersActive: Int,
    val reservations: Long,
    val visitsCompleted: Long,
    val smsSent: Long,
    val errors: Long,
    val healthScore: Int
)

data class TenantUserUsage(
    val userId: String,
    val fullName: String,
    val email: String,
    /** OWNER or EMPLOYEE — the split the requirement asks for, per person. */
    val actorKind: String,
    val activeHours: Double,
    val sessions: Long,
    val avgSessionMinutes: Long,
    val lastSeenAt: Instant?
)

/**
 * Time-to-value milestones. A studio that has not created its first visit two weeks after
 * signing up will not renew, and this is where that is visible while onboarding can still
 * intervene.
 */
data class ActivationMilestones(
    val signedUpAt: Instant,
    val firstLoginAt: Instant?,
    val firstCustomerAt: Instant?,
    val firstReservationAt: Instant?,
    val firstVisitCompletedAt: Instant?,
    val daysToFirstReservation: Long?,
    val daysToFirstVisitCompleted: Long?,
    val fullyActivated: Boolean
)

/**
 * Which modules a tenant actually uses, measured by traffic to that module's endpoints.
 *
 * The commercially important case is `paidFor = true, calls = 0`: a customer paying for an
 * add-on they never open. That is both a churn predictor and, handled well, a retention
 * conversation — nobody renews a line item they cannot remember using.
 */
data class FeatureAdoption(
    val module: String,
    val calls: Long,
    val lastUsedAt: Instant?,
    val paidFor: Boolean,
    val adopted: Boolean
)

// ── Dead endpoints ────────────────────────────────────────────────────────────

data class DeadEndpointReport(
    val observationStartedAt: Instant?,
    val observationDays: Long,
    /**
     * False until the audit has been collecting for `min-observation-days`. The console
     * shows a warning instead of a delete list — recommending removal of a
     * quarterly-report endpoint on day three is exactly how a tool like this loses trust.
     */
    val reliable: Boolean,
    val totalEndpoints: Int,
    val summary: Map<String, Int>,
    val endpoints: List<EndpointUsageRow>
)

data class EndpointUsageRow(
    val method: String,
    val path: String,
    val controller: String,
    val handler: String,
    val module: String,
    val vitality: EndpointVitality,
    val vitalityLabel: String,
    val lastCalledAt: Instant?,
    val daysSinceLastCall: Long?,
    val totalCalls: Long,
    val calls30d: Long,
    val distinctStudios30d: Int,
    val avgDurationMs: Long,
    val errorRate30dPercent: Double,
    val requiresAuth: Boolean,
    val retentionExempt: Boolean,
    /** Plain-language recommendation the reader can act on without re-deriving the rule. */
    val recommendation: String
)

// ── Errors ────────────────────────────────────────────────────────────────────

data class ErrorGroupRow(
    val fingerprint: String,
    val title: String,
    val origin: String,
    val severity: String,
    val status: String,
    val occurrences: Long,
    val affectedStudios: Int,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val topStudios: List<AffectedStudio>
)

data class AffectedStudio(
    val studioId: String,
    val studioName: String,
    val occurrences: Long,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant
)

data class ErrorGroupDetail(
    val group: ErrorGroupRow,
    val recentOccurrences: List<ErrorOccurrence>
)

data class ErrorOccurrence(
    val id: String,
    val studioId: String?,
    val studioName: String?,
    val userId: String?,
    val occurredAt: Instant,
    val message: String?,
    val path: String?,
    val httpStatus: Int?,
    val correlationId: String?,
    val appVersion: String?,
    val stackTrace: String?
)

data class UpdateErrorGroupRequest(
    /** NEW | ACKNOWLEDGED | RESOLVED | IGNORED */
    val status: String,
    val note: String? = null,
    val resolvedInVersion: String? = null
)

// ── Sessions ──────────────────────────────────────────────────────────────────

data class SessionAnalyticsResponse(
    val from: LocalDate,
    val to: LocalDate,
    val totalActiveHours: Double,
    val ownerActiveHours: Double,
    val employeeActiveHours: Double,
    val meaningfulSessions: Long,
    /**
     * Sessions the filter rejected. Surfaced deliberately: it is the proof that the
     * "empty session" problem is being handled, and a sudden change in the ratio means
     * the client-side heartbeat broke long before the usage chart starts looking odd.
     */
    val discardedSessions: Long,
    val discardedRatioPercent: Double,
    val avgSessionMinutes: Long,
    val medianSessionMinutes: Long,
    val topStudios: List<StudioSessionRow>
)

data class StudioSessionRow(
    val studioId: String,
    val studioName: String,
    val activeHours: Double,
    val ownerHours: Double,
    val employeeHours: Double,
    val sessions: Long,
    val activeUsers: Int
)

// ── Retention / churn board ───────────────────────────────────────────────────

data class TenantHealthRow(
    val studioId: String,
    val studioName: String,
    val planKey: String,
    val subscriptionStatus: String,
    val healthScore: Int,
    val churnRisk: String,
    val daysSinceLastActivity: Long?,
    val activeMinutes14d: Long,
    val activeMinutesPrior14d: Long,
    val trendPercent: Double,
    val reservations14d: Long,
    val seatsTotal: Int,
    val seatsActive: Int,
    val mrrGrossCents: Long,
    /** Why the score is what it is, in plain language, ranked by impact. */
    val signals: List<String>
)
