package pl.detailing.crm.inbound.reject

import pl.detailing.crm.shared.CallId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class RejectCallCommand(
    val callId: CallId,
    val studioId: StudioId,
    val userId: UserId
)
