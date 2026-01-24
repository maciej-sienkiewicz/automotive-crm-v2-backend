package pl.detailing.crm.inbound.update

import pl.detailing.crm.shared.CallId
import pl.detailing.crm.shared.StudioId

data class UpdateCallCommand(
    val callId: CallId,
    val studioId: StudioId,
    val callerName: String?,
    val note: String?
)
