package pl.detailing.crm.dashboard.reservationsummary

data class GetDashboardReservationSummaryResult(
    val currentWeek: WeekReservations,
    val previousWeek: WeekReservations,
    val deltaPercentage: Double,
    val buckets: List<WeeklyReservationBucket>
)

data class WeekReservations(
    val count: Long
)

data class WeeklyReservationBucket(
    val weekStart: String,
    val count: Long
)
