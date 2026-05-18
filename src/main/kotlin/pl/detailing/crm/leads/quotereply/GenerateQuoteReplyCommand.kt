package pl.detailing.crm.leads.quotereply

import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId

data class GenerateQuoteReplyCommand(
    val leadId: LeadId,
    val studioId: StudioId,
    val userName: String
)

data class GenerateQuoteReplyResult(
    val title: String,
    val reply: String
)
