package pl.detailing.crm.dashboard.domain

import java.time.Instant
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.VisitId

/**
 * Visit detail for operational stats hover/tooltip
 */
data class VisitDetail(
    val id: VisitId,
    val brand: String,
    val model: String,
    val amount: Money,
    val customerFirstName: String,
    val customerLastName: String,
    val phoneNumber: String?,
    val estimatedCompletionDate: Instant?
)

/**
 * Operational statistics showing current work status
 */
data class OperationalStats(
    val inProgress: Int,
    val readyForPickup: Int,
    val incomingToday: Int,
    val overdue: Int,
    val inProgressDetails: List<VisitDetail>,
    val readyForPickupDetails: List<VisitDetail>,
    val incomingTodayDetails: List<VisitDetail>
)

/**
 * Business metric with comparison to previous period
 */
data class BusinessMetric(
    val currentValue: Long,
    val previousValue: Long,
    val deltaPercentage: Double,
    val unit: String
)

/**
 * Incoming call for dashboard display
 */
data class IncomingCallSummary(
    val id: String,
    val phoneNumber: String,
    val contactName: String?,
    val timestamp: String,
    val note: String?
)

/**
 * Complete dashboard summary
 */
data class DashboardSummary(
    val stats: OperationalStats,
    val revenue: BusinessMetric,
    val callActivity: BusinessMetric,
    val recentCalls: List<IncomingCallSummary>
)
