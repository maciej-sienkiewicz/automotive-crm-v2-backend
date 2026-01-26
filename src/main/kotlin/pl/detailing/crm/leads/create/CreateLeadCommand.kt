package pl.detailing.crm.leads.create

import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.StudioId

data class CreateLeadCommand(
    val studioId: StudioId,
    val source: LeadSource,
    val contactIdentifier: String,
    val customerName: String?,
    val initialMessage: String?,
    val estimatedValue: Long
)
