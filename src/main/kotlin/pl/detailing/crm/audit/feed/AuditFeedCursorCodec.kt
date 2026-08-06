package pl.detailing.crm.audit.feed

import pl.detailing.crm.audit.infrastructure.AuditFeedCursor
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Encodes a feed position as an opaque string.
 *
 * Opaque on purpose: the client is only expected to send back whatever it was given, which
 * leaves the ordering key free to change without a client release.
 *
 * The nanosecond component is part of the key, not rounded away — `created_at` has
 * microsecond precision, and a burst of entries from a single transaction shares a
 * timestamp down to the millisecond. Truncating would make the page boundary skip or
 * repeat exactly those bursts.
 */
object AuditFeedCursorCodec {

    /** `<epochSecond>.<nano>:<uuid>`, base64url without padding. */
    fun encode(cursor: AuditFeedCursor): String {
        val raw = "${cursor.createdAt.epochSecond}.${cursor.createdAt.nano}:${cursor.id}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
    }

    /** Returns null for anything unreadable — a stale cursor restarts the feed, it does not fail it. */
    fun decode(encoded: String?): AuditFeedCursor? {
        if (encoded.isNullOrBlank()) return null
        return try {
            val raw = String(Base64.getUrlDecoder().decode(encoded))
            val timestamp = raw.substringBefore(':')
            val id = raw.substringAfter(':', "")
            if (id.isEmpty()) return null
            AuditFeedCursor(
                createdAt = Instant.ofEpochSecond(
                    timestamp.substringBefore('.').toLong(),
                    timestamp.substringAfter('.', "0").toLong()
                ),
                id = UUID.fromString(id)
            )
        } catch (e: Exception) {
            null
        }
    }
}
