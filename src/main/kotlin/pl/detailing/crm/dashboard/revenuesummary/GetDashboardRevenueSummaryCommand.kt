package pl.detailing.crm.dashboard.revenuesummary

import pl.detailing.crm.shared.StudioId

data class GetDashboardRevenueSummaryCommand(
    val studioId: StudioId,
    val weeks: Int = 13
)
