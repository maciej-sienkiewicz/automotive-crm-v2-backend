package pl.detailing.crm.dashboard.reservationsummary

import pl.detailing.crm.shared.StudioId

data class GetDashboardReservationSummaryCommand(
    val studioId: StudioId,
    val months: Int = 12
)
