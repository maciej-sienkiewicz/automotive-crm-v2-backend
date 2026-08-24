package pl.detailing.crm.push.domain

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * A browser (typically the user's phone PWA) that agreed to receive
 * Click-to-Call pushes. The endpoint + p256dh + auth triple is everything
 * needed to deliver an encrypted Web Push message to that device.
 */
data class PushDevice(
    val id: UUID,
    val studioId: StudioId,
    val userId: UserId,
    val deviceName: String,
    val userAgent: String?,
    val endpoint: String,
    val p256dh: String,
    val auth: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val revokedAt: Instant?
) {
    val isActive: Boolean get() = revokedAt == null
}
