package pl.detailing.crm.auth.login

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations

class AccountLockoutServiceTest {

    private val redis = mockk<StringRedisTemplate>(relaxed = true)
    private val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
    private val service = AccountLockoutService(redis)

    init {
        every { redis.opsForValue() } returns valueOps
    }

    @Test
    fun `fifth failure locks the account and the counter is reset`() {
        every { valueOps.increment("auth:attempts:user@x.pl") } returnsMany listOf(1L, 2L, 3L, 4L, 5L)

        val outcomes = (1..5).map { service.recordFailure("User@X.pl ") }

        assertFalse(outcomes.take(4).any { it })
        assertTrue(outcomes.last())
        verify { valueOps.set("auth:lockout:user@x.pl", any<String>(), AccountLockoutService.LOCKOUT_DURATION) }
        verify { redis.delete("auth:attempts:user@x.pl") }
    }

    @Test
    fun `isLocked normalises the e-mail the same way as recordFailure`() {
        every { redis.hasKey("auth:lockout:user@x.pl") } returns true
        assertTrue(service.isLocked("  USER@x.PL"))
    }
}
