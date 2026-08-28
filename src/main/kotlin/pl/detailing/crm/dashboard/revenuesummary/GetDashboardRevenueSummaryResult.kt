package pl.detailing.crm.dashboard.revenuesummary

data class GetDashboardRevenueSummaryResult(
    val currentMonth: MonthRevenue,
    val previousMonth: MonthRevenue,
    val deltaPercentage: Double,
    val buckets: List<MonthlyRevenueBucket>
)

data class MonthRevenue(
    val grossAmount: Long,
    val currency: String
)

data class MonthlyRevenueBucket(
    /** Pierwszy dzień miesiąca, ISO (np. 2026-08-01). */
    val monthStart: String,
    val grossAmount: Long,
    val currency: String
)
