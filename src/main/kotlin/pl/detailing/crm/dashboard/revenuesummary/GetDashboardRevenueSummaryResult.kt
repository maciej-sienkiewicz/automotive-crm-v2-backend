package pl.detailing.crm.dashboard.revenuesummary

data class GetDashboardRevenueSummaryResult(
    val currentWeek: WeekRevenue,
    val previousWeek: WeekRevenue,
    val deltaPercentage: Double,
    val buckets: List<WeeklyRevenueBucket>
)

data class WeekRevenue(
    val grossAmount: Long,
    val currency: String
)

data class WeeklyRevenueBucket(
    val weekStart: String,
    val grossAmount: Long,
    val currency: String
)
