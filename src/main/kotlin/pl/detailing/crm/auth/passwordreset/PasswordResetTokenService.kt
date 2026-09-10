package pl.detailing.crm.auth.passwordreset

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * Issues, validates and consumes one-time password reset tokens backed by Redis.
 *
 * Only a SHA-256 hash of the token is stored, so a leak of the Redis contents
 * does not expose usable reset links. Tokens are single-use and expire after the
 * configured TTL. The same mechanism carries employee invitation links; they only
 * differ in TTL (see [issueInvitationToken]).
 */
@Service
class PasswordResetTokenService(
    private val redisTemplate: StringRedisTemplate,
    private val properties: PasswordResetProperties
) {
    companion object {
        private const val TOKEN_KEY_PREFIX = "auth:pwreset:token:"
        private const val COOLDOWN_KEY_PREFIX = "auth:pwreset:cooldown:"
        private const val TOKEN_BYTES = 32
    }

    private val secureRandom = SecureRandom()

    /** Generates a fresh single-use password reset token for [userId] and returns the raw value to embed in the link. */
    fun issueToken(userId: UUID): String =
        issueToken(userId, Duration.ofMinutes(properties.tokenTtlMinutes))

    /**
     * Generates a fresh single-use account setup token for a newly invited employee.
     * Consumed by the same endpoint as a reset token, but lives for
     * [PasswordResetProperties.invitationTokenTtlHours] instead of minutes.
     */
    fun issueInvitationToken(userId: UUID): String =
        issueToken(userId, Duration.ofHours(properties.invitationTokenTtlHours))

    private fun issueToken(userId: UUID, ttl: Duration): String {
        val rawToken = generateRawToken()
        redisTemplate.opsForValue().set(tokenKey(rawToken), userId.toString(), ttl)
        return rawToken
    }

    /** Returns the owning user id and atomically invalidates the token, or null if it is unknown/expired. */
    fun consumeToken(rawToken: String): UUID? {
        if (rawToken.isBlank()) return null
        val key = tokenKey(rawToken)
        val userId = redisTemplate.opsForValue().get(key) ?: return null
        redisTemplate.delete(key)
        return runCatching { UUID.fromString(userId) }.getOrNull()
    }

    /** Non-destructive check used by the frontend to decide whether to render the reset form. */
    fun isValid(rawToken: String): Boolean =
        rawToken.isNotBlank() && redisTemplate.hasKey(tokenKey(rawToken)) == true

    /**
     * Marks [email] as having just requested a reset. Returns true when the request
     * is allowed to proceed, false when a reset was already requested within the
     * cooldown window (so the caller should silently skip sending another email).
     */
    fun tryStartCooldown(email: String): Boolean {
        val acquired = redisTemplate.opsForValue().setIfAbsent(
            cooldownKey(email),
            "1",
            Duration.ofSeconds(properties.requestCooldownSeconds)
        )
        return acquired == true
    }

    private fun generateRawToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun tokenKey(rawToken: String): String = "$TOKEN_KEY_PREFIX${sha256(rawToken)}"

    private fun cooldownKey(email: String): String = "$COOLDOWN_KEY_PREFIX${sha256(email)}"

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
