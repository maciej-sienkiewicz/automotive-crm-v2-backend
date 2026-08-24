package pl.detailing.crm.push

import pl.detailing.crm.push.domain.PushDevice
import pl.detailing.crm.shared.pii.Pii
import java.time.Instant

// ─── Requests ─────────────────────────────────────────────────────────────────

/**
 * Body of POST /api/v1/push/devices — the browser's PushSubscription flattened.
 * `endpoint` + `p256dh` + `auth` come verbatim from
 * `registration.pushManager.subscribe(...)` on the phone.
 */
data class RegisterPushDeviceRequest(
    val endpoint: String,
    val p256dh: String,
    val auth: String,
    val deviceName: String,
    val userAgent: String? = null
)

/** Body of POST /api/v1/push/call-requests — the desktop's "Zadzwoń" click. */
data class RequestCallRequest(
    @Pii val phoneNumber: String,
    @Pii val displayName: String? = null,
    /** Optional: target a single paired device instead of all of them. */
    val deviceId: String? = null
)

// ─── Responses ────────────────────────────────────────────────────────────────

data class VapidPublicKeyResponse(
    val publicKey: String
)

data class PushDeviceDto(
    val id: String,
    val deviceName: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val active: Boolean
)

data class RequestCallResponse(
    val requestedDevices: Int,
    val deliveredDevices: Int
)

fun PushDevice.toDto(): PushDeviceDto = PushDeviceDto(
    id = id.toString(),
    deviceName = deviceName,
    createdAt = createdAt,
    lastUsedAt = lastUsedAt,
    active = isActive
)
