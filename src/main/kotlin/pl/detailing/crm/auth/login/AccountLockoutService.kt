package pl.detailing.crm.auth.login

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * Per-account failed-password counter and temporary lockout, shared by EVERY path that
 * verifies a user's account password: the JSON login and CardDAV HTTP Basic.
 *
 * It used to live inside [LoginHandler] only, so the CardDAV endpoint accepted unlimited
 * password guesses for the same accounts — a lockout is only as strong as its weakest
 * entry point.
 *
 * Keys are per e-mail (not per IP) and only ever touched for e-mails that exist, which
 * keeps the counter from becoming an account-existence oracle.
 */
@Service
class AccountLockoutService(
    private val redisTemplate: StringRedisTemplate
) {
    companion object {
        const val LOCKOUT_KEY_PREFIX = "auth:lockout:"
        const val ATTEMPTS_KEY_PREFIX = "auth:attempts:"
        const val MAX_ATTEMPTS = 5
        val LOCKOUT_DURATION: Duration = Duration.ofMinutes(15)
        val ATTEMPTS_WINDOW: Duration = Duration.ofMinutes(15)
    }

    fun isLocked(email: String): Boolean =
        redisTemplate.hasKey("$LOCKOUT_KEY_PREFIX${normalize(email)}") == true

    /**
     * Records one failed attempt. Returns true when this attempt tripped the lock.
     * Atomic (Redis INCR): concurrent guesses cannot slip under the threshold.
     */
    fun recordFailure(email: String): Boolean {
        val key = normalize(email)
        val attemptsKey = "$ATTEMPTS_KEY_PREFIX$key"
        val attempts = redisTemplate.opsForValue().increment(attemptsKey) ?: 1L
        if (attempts == 1L) redisTemplate.expire(attemptsKey, ATTEMPTS_WINDOW)

        if (attempts >= MAX_ATTEMPTS) {
            redisTemplate.opsForValue().set("$LOCKOUT_KEY_PREFIX$key", Instant.now().toString(), LOCKOUT_DURATION)
            redisTemplate.delete(attemptsKey)
            return true
        }
        return false
    }

    fun clear(email: String) {
        val key = normalize(email)
        redisTemplate.delete("$ATTEMPTS_KEY_PREFIX$key")
        redisTemplate.delete("$LOCKOUT_KEY_PREFIX$key")
    }

    private fun normalize(email: String) = email.lowercase().trim()
}
