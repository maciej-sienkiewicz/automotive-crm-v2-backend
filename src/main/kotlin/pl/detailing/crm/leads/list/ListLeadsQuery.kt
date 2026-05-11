package pl.detailing.crm.leads.list

import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import java.time.Instant

data class ListLeadsQuery(
    val studioId: StudioId,
    val search: String?,
    val statuses: List<LeadStatus>?,
    val sources: List<LeadSource>?,
    val page: Int,
    val limit: Int,
    val dateFrom: Instant? = null,
    val dateTo: Instant? = null
)
