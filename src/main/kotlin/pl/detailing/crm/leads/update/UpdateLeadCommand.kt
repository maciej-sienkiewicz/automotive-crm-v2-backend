package pl.detailing.crm.leads.update

import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId

data class UpdateLeadCommand(
    val leadId: LeadId,
    val studioId: StudioId,
    val status: LeadStatus?,
    val customerName: String?,
    val initialMessage: String?,
    val estimatedValue: Long?
)
