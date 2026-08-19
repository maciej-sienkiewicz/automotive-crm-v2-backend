package pl.detailing.crm.metrics.query

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import pl.detailing.crm.metrics.apiaudit.ApiUsageBuffer
import pl.detailing.crm.metrics.billing.SubscriptionMetricsService
import pl.detailing.crm.metrics.domain.ErrorGroupStatus
import pl.detailing.crm.metrics.domain.MetricsClock
import pl.detailing.crm.metrics.infrastructure.ErrorGroupRepository
import pl.detailing.crm.metrics.infrastructure.PlatformDailySnapshotRepository
import pl.detailing.crm.metrics.ingest.MetricEventRecorder
import pl.detailing.crm.metrics.session.SessionActivityTracker
import java.time.LocalDate

/**
 * Front page of the platform console.
 *
 * Subscriptions come from the **live** service (the requirement is real-time) while
 * engagement and activity come from the day's snapshot. Mixing the two is intentional:
 * "how many customers do we have" must be current to the minute, whereas "how many
 * reservations today" is inherently a running total that nobody needs sub-minute.
 */
@Service
class GetPlatformOverviewHandler(
    private val subscriptionMetrics: SubscriptionMetricsService,
    private val platformSnapshots: PlatformDailySnapshotRepository,
    private val errorGroups: ErrorGroupRepository,
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val recorder: MetricEventRecorder,
    private val apiBuffer: ApiUsageBuffer,
    private val sessionTracker: SessionActivityTracker
) {

    fun handle(date: LocalDate = MetricsClock.today()): PlatformOverviewResponse {
        val subs = subscriptionMetrics.snapshot()
        val snapshot = platformSnapshots.findById(date).orElse(null)

        val activeStudios = snapshot?.dauStudios ?: 0
        val activeMinutes = snapshot?.activeMinutesTotal ?: 0

        return PlatformOverviewResponse(
            asOf = date,
            subscriptions = SubscriptionOverview(
                studiosTotal = subs.total,
                paying = subs.payingStudios,
                trialing = subs.byStatus["TRIALING"] ?: 0,
                expired = subs.byStatus["EXPIRED"] ?: 0,
                withoutPlan = subs.studiosWithoutPlan,
                planBasic = subs.planCount("BASIC"),
                planFull = subs.planCount("FULL"),
                mrrGrossCents = subs.mrrGrossCents,
                arpaGrossCents = subs.arpaGrossCents,
                addOnRevenueGrossCents = subs.addOnRevenueGrossCents,
                activeAddOns = subs.activeAddOnsByKey,
                computedAt = subs.computedAt
            ),
            engagement = EngagementOverview(
                dauStudios = activeStudios,
                wauStudios = snapshot?.wauStudios ?: 0,
                mauStudios = snapshot?.mauStudios ?: 0,
                dauUsers = snapshot?.dauUsers ?: 0,
                stickinessPercent = (snapshot?.stickinessPermille ?: 0) / 10.0,
                activeMinutesTotal = activeMinutes,
                avgMinutesPerActiveStudio = if (activeStudios == 0) 0 else activeMinutes / activeStudios
            ),
            activity = ActivityOverview(
                reservationsCreated = snapshot?.reservationsCreated ?: 0,
                visitsCompleted = snapshot?.visitsCompleted ?: 0,
                smsSent = snapshot?.smsSent ?: 0,
                emailsSent = snapshot?.emailsSent ?: 0,
                apiCalls = snapshot?.apiCalls ?: 0
            ),
            reliability = ReliabilityOverview(
                errorsTotal = snapshot?.errorsTotal ?: 0,
                newErrorGroups = snapshot?.errorGroupsNew ?: 0,
                studiosWithErrors = snapshot?.studiosWithErrors ?: 0,
                openErrorGroups = countOpenGroups()
            ),
            pipeline = pipelineHealth()
        )
    }

    /**
     * A number the console must show even though it is about the console itself.
     * Dashboards that cannot report their own data loss are how a company spends a quarter
     * confidently acting on numbers that stopped being complete in week two.
     */
    private fun pipelineHealth(): PipelineHealth {
        val stats = recorder.stats()
        return PipelineHealth(
            eventQueueDepth = stats.queued,
            eventQueueCapacity = stats.capacity,
            eventsDropped = stats.dropped,
            apiBufferKeys = apiBuffer.pendingKeys(),
            apiEventsDropped = apiBuffer.droppedEvents(),
            openSessionsTracked = sessionTracker.trackedOpenSessions(),
            healthy = stats.healthy && apiBuffer.droppedEvents() == 0L
        )
    }

    private fun countOpenGroups(): Int =
        errorGroups.findByStatusOrderByLastSeen(ErrorGroupStatus.NEW).size +
            errorGroups.findByStatusOrderByLastSeen(ErrorGroupStatus.ACKNOWLEDGED).size

    /** Time series for the console's charts. */
    fun trend(from: LocalDate, to: LocalDate): List<Map<String, Any?>> =
        jdbcTemplate.query(
            """
            SELECT snapshot_date, studios_total, studios_paying, studios_trialing,
                   mrr_gross_cents, dau_studios, wau_studios, mau_studios,
                   stickiness_permille, active_minutes_total, reservations_created,
                   visits_completed, sms_sent, errors_total, new_signups, churned
            FROM metric_daily_platform_snapshots
            WHERE snapshot_date BETWEEN :from AND :to
            ORDER BY snapshot_date
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("from", java.sql.Date.valueOf(from))
                .addValue("to", java.sql.Date.valueOf(to))
        ) { rs, _ ->
            mapOf(
                "date" to rs.getDate("snapshot_date").toLocalDate(),
                "studiosTotal" to rs.getInt("studios_total"),
                "studiosPaying" to rs.getInt("studios_paying"),
                "studiosTrialing" to rs.getInt("studios_trialing"),
                "mrrGrossCents" to rs.getLong("mrr_gross_cents"),
                "dauStudios" to rs.getInt("dau_studios"),
                "wauStudios" to rs.getInt("wau_studios"),
                "mauStudios" to rs.getInt("mau_studios"),
                "stickinessPercent" to rs.getInt("stickiness_permille") / 10.0,
                "activeMinutes" to rs.getLong("active_minutes_total"),
                "reservations" to rs.getLong("reservations_created"),
                "visitsCompleted" to rs.getLong("visits_completed"),
                "smsSent" to rs.getLong("sms_sent"),
                "errors" to rs.getLong("errors_total"),
                "newSignups" to rs.getInt("new_signups"),
                "churned" to rs.getInt("churned")
            )
        }
}
