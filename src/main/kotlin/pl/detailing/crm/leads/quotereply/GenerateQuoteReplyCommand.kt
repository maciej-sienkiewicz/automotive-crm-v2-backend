package pl.detailing.crm.leads.quotereply

import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId

data class GenerateQuoteReplyCommand(
    val leadId: LeadId,
    val studioId: StudioId
)

data class GenerateQuoteReplyResult(
    val reply: String
)
