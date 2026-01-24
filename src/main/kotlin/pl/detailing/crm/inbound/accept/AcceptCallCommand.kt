package pl.detailing.crm.inbound.accept

import pl.detailing.crm.shared.CallId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class AcceptCallCommand(
    val callId: CallId,
    val studioId: StudioId,
    val userId: UserId
)
