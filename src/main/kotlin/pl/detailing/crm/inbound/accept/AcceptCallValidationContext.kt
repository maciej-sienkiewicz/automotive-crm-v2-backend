package pl.detailing.crm.inbound.accept

import pl.detailing.crm.inbound.domain.CallLog
import pl.detailing.crm.shared.CallId
import pl.detailing.crm.shared.StudioId

data class AcceptCallValidationContext(
    val callId: CallId,
    val studioId: StudioId,
    val callLog: CallLog?
)
