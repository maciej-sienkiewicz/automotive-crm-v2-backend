package pl.detailing.crm.signing.infrastructure

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * Cryptographic primitives of the WYSIWYS loop:
 *
 *  - SHA-256 document digests binding a signature to the exact PDF revision displayed
 *    on the tablet ("What You See Is What You Sign", eIDAS).
 *  - Single-use challenge nonces preventing replay of a captured signature submission
 *    ("atak przez powtórzenie"): the challenge is issued when the request is created,
 *    delivered to the tablet together with the document, and consumed ATOMICALLY
 *    (Redis GETDEL) on submission — a second submission with the same challenge fails.
 */
@Service
class DocumentIntegrityService(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${signing.request.ttl-minutes:15}") private val requestTtlMinutes: Long
) {
    companion object {
        private val SECURE_RANDOM = SecureRandom()
        private const val CHALLENGE_KEY_PREFIX = "signing:challenge:"
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Constant-time comparison of two hex digests (defense against timing side channels). */
    fun digestsMatch(expectedHex: String, actualHex: String): Boolean {
        val a = expectedHex.lowercase().toByteArray()
        val b = actualHex.lowercase().toByteArray()
        return MessageDigest.isEqual(a, b)
    }

    /**
     * Issue a single-use challenge nonce bound to the signature request.
     * [ttl] must match the request's lifetime (SMS-link sessions live longer than tablet ones).
     */
    fun issueChallenge(requestId: UUID, ttl: Duration = Duration.ofMinutes(requestTtlMinutes)): String {
        val bytes = ByteArray(32)
        SECURE_RANDOM.nextBytes(bytes)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        redisTemplate.opsForValue().set(CHALLENGE_KEY_PREFIX + requestId, challenge, ttl)
        return challenge
    }

    /** Read the challenge without consuming it (needed to deliver it to the tablet). */
    fun peekChallenge(requestId: UUID): String? =
        redisTemplate.opsForValue().get(CHALLENGE_KEY_PREFIX + requestId)

    /**
     * Atomically consume the challenge. Returns true only for the FIRST submission
     * carrying the correct nonce; every subsequent attempt (replay) returns false.
     */
    fun consumeChallenge(requestId: UUID, submittedChallenge: String): Boolean {
        val stored = redisTemplate.opsForValue().getAndDelete(CHALLENGE_KEY_PREFIX + requestId)
            ?: return false
        return MessageDigest.isEqual(stored.toByteArray(), submittedChallenge.toByteArray())
    }

    fun invalidateChallenge(requestId: UUID) {
        redisTemplate.delete(CHALLENGE_KEY_PREFIX + requestId)
    }
}
