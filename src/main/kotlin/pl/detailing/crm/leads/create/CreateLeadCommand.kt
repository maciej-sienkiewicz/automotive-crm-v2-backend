package pl.detailing.crm.leads.create

import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class CreateLeadCommand(
    val studioId: StudioId,
    val userId: UserId? = null,
    val source: LeadSource,
    val contactIdentifier: String,
    val customerName: String?,
    val initialMessage: String?,
    val estimatedValue: Long,
    val userName: String? = null
)
