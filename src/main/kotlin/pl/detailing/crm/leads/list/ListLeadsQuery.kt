package pl.detailing.crm.leads.list

import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

data class ListLeadsQuery(
    val studioId: StudioId,
    val search: String?,
    /** Whether search may match personal-data columns; false = oracle-safe (message only). */
    val includePiiSearch: Boolean = false,
    val statuses: List<LeadStatus>?,
    val sources: List<LeadSource>?,
    val page: Int,
    val limit: Int,
    val dateFrom: Instant? = null,
    val dateTo: Instant? = null,
    val valueMin: Long? = null,
    val valueMax: Long? = null,
    val assignedUserId: UUID? = null
)
