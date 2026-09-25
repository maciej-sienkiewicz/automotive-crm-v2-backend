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

    val platform: PushDevicePlatform get() = PushDevicePlatform.fromUserAgent(userAgent)
}

/**
 * What kind of device holds the subscription, read from the User-Agent it sent
 * when pairing.
 *
 * Needed for one question the phone cannot answer by itself: "is CRM already on
 * this iPhone's home screen?". On iOS a home-screen web app is invisible to Safari
 * (separate cookies, separate storage, no API to detect or open it), so a user who
 * installed the app and later opened the site in Safari was told to install it
 * again. An active IOS device on the account is the best available hint that the
 * app is already there.
 *
 * Only a hint: iOS freezes its User-Agent, so two iPhones look the same. And an
 * iPad in desktop mode claims to be a Mac - it lands in DESKTOP, which errs on
 * the side of showing the install steps rather than a wrong "you already have it".
 */
enum class PushDevicePlatform {
    IOS,
    ANDROID,
    DESKTOP,
    UNKNOWN;

    companion object {
        private val IOS_UA = Regex("iphone|ipad|ipod", RegexOption.IGNORE_CASE)
        private val ANDROID_UA = Regex("android", RegexOption.IGNORE_CASE)
        private val DESKTOP_UA = Regex("windows|macintosh|x11|linux|cros", RegexOption.IGNORE_CASE)

        fun fromUserAgent(userAgent: String?): PushDevicePlatform = when {
            userAgent.isNullOrBlank() -> UNKNOWN
            IOS_UA.containsMatchIn(userAgent) -> IOS
            // Android przed desktopem: User-Agent Androida też zawiera „Linux".
            ANDROID_UA.containsMatchIn(userAgent) -> ANDROID
            DESKTOP_UA.containsMatchIn(userAgent) -> DESKTOP
            else -> UNKNOWN
        }
    }
}
