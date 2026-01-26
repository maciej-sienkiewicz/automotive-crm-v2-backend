package pl.detailing.crm.leads.delete

import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId

data class DeleteLeadCommand(
    val leadId: LeadId,
    val studioId: StudioId
)
