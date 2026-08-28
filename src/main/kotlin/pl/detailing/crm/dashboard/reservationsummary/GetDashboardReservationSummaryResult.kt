package pl.detailing.crm.dashboard.reservationsummary

data class GetDashboardReservationSummaryResult(
    val currentMonth: MonthReservations,
    val previousMonth: MonthReservations,
    val deltaPercentage: Double,
    val buckets: List<MonthlyReservationBucket>
)

data class MonthReservations(
    val count: Long
)

data class MonthlyReservationBucket(
    /** Pierwszy dzień miesiąca, ISO (np. 2026-08-01). */
    val monthStart: String,
    val count: Long
)
