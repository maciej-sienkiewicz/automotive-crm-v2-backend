package pl.detailing.crm.leads.summary

import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.StudioId
import java.time.Instant

data class GetPipelineSummaryQuery(
    val studioId: StudioId,
    val sourceFilter: List<LeadSource>?,
    val dateFrom: Instant? = null,
    val dateTo: Instant? = null
)
