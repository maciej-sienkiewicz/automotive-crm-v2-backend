package pl.detailing.crm.dashboard

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.dashboard.query.GetDashboardSummaryCommand
import pl.detailing.crm.dashboard.query.GetDashboardSummaryHandler

@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController(
    private val getDashboardSummaryHandler: GetDashboardSummaryHandler
) {

    /**
     * Get dashboard statistics and metrics
     * GET /api/v1/dashboard/stats
     */
    @GetMapping("/stats")
    fun getStats(): ResponseEntity<DashboardDataResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = GetDashboardSummaryCommand(
            studioId = principal.studioId
        )

        val summary = getDashboardSummaryHandler.handle(command)

        val response = DashboardDataResponse(
            stats = OperationalStatsResponse(
                inProgress = summary.stats.inProgress,
                readyForPickup = summary.stats.readyForPickup,
                incomingToday = summary.stats.incomingToday,
                inProgressDetails = summary.stats.inProgressDetails.map { detail ->
                    VisitDetailResponse(
                        id = detail.id.toString(),
                        brand = detail.brand,
                        model = detail.model,
                        amount = detail.amount.amountInCents / 100.0,
                        customerFirstName = detail.customerFirstName,
                        customerLastName = detail.customerLastName,
                        phoneNumber = detail.phoneNumber
                    )
                },
                readyForPickupDetails = summary.stats.readyForPickupDetails.map { detail ->
                    VisitDetailResponse(
                        id = detail.id.toString(),
                        brand = detail.brand,
                        model = detail.model,
                        amount = detail.amount.amountInCents / 100.0,
                        customerFirstName = detail.customerFirstName,
                        customerLastName = detail.customerLastName,
                        phoneNumber = detail.phoneNumber
                    )
                },
                incomingTodayDetails = summary.stats.incomingTodayDetails.map { detail ->
                    VisitDetailResponse(
                        id = detail.id.toString(),
                        brand = detail.brand,
                        model = detail.model,
                        amount = detail.amount.amountInCents / 100.0,
                        customerFirstName = detail.customerFirstName,
                        customerLastName = detail.customerLastName,
                        phoneNumber = detail.phoneNumber
                    )
                }
            ),
            revenue = BusinessMetricResponse(
                currentValue = summary.revenue.currentValue / 100.0,
                previousValue = summary.revenue.previousValue / 100.0,
                deltaPercentage = summary.revenue.deltaPercentage,
                unit = summary.revenue.unit
            ),
            callActivity = BusinessMetricResponse(
                currentValue = summary.callActivity.currentValue.toDouble(),
                previousValue = summary.callActivity.previousValue.toDouble(),
                deltaPercentage = summary.callActivity.deltaPercentage,
                unit = summary.callActivity.unit
            ),
            recentCalls = summary.recentCalls.map { call ->
                IncomingCallResponse(
                    id = call.id,
                    phoneNumber = call.phoneNumber,
                    contactName = call.contactName,
                    timestamp = call.timestamp,
                    note = call.note
                )
            },
            googleReviews = null // Skipped as per requirements
        )

        ResponseEntity.ok(response)
    }
}

/**
 * Response DTOs matching frontend TypeScript types
 */
data class VisitDetailResponse(
    val id: String,
    val brand: String,
    val model: String,
    val amount: Double,
    val customerFirstName: String,
    val customerLastName: String,
    val phoneNumber: String?
)

data class OperationalStatsResponse(
    val inProgress: Int,
    val readyForPickup: Int,
    val incomingToday: Int,
    val inProgressDetails: List<VisitDetailResponse>,
    val readyForPickupDetails: List<VisitDetailResponse>,
    val incomingTodayDetails: List<VisitDetailResponse>
)

data class BusinessMetricResponse(
    val currentValue: Double,
    val previousValue: Double,
    val deltaPercentage: Double,
    val unit: String
)

data class IncomingCallResponse(
    val id: String,
    val phoneNumber: String,
    val contactName: String?,
    val timestamp: String,
    val note: String?
)

data class DashboardDataResponse(
    val stats: OperationalStatsResponse,
    val revenue: BusinessMetricResponse,
    val callActivity: BusinessMetricResponse,
    val recentCalls: List<IncomingCallResponse>,
    val googleReviews: Any? // Skipped, set to null
)
