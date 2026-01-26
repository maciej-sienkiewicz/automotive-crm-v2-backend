package pl.detailing.crm.leads.summary

import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.StudioId

data class GetPipelineSummaryQuery(
    val studioId: StudioId,
    val sourceFilter: List<LeadSource>?
)
