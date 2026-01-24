package pl.detailing.crm.inbound.register

import pl.detailing.crm.shared.StudioId
import java.time.Instant

data class RegisterInboundCallCommand(
    val studioId: StudioId,
    val phoneNumber: String,
    val callerName: String?,
    val note: String?,
    val receivedAt: Instant = Instant.now()
)
