package pl.detailing.crm.push.register

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class RegisterPushDeviceCommand(
    val studioId: StudioId,
    val userId: UserId,
    val endpoint: String,
    val p256dh: String,
    val auth: String,
    val deviceName: String,
    val userAgent: String?
)
