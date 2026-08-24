package pl.detailing.crm.push.call

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.util.UUID

data class RequestCallCommand(
    val studioId: StudioId,
    val userId: UserId,
    val requestedByName: String,
    val phoneNumber: String,
    /** Name shown on the phone's notification, e.g. "Maciej S." */
    val displayName: String?,
    /** When set, push goes only to this device; otherwise to all active ones. */
    val deviceId: UUID?
)

data class RequestCallResult(
    val requestedDevices: Int,
    val deliveredDevices: Int,
    val revokedDevices: Int
)
