package pl.detailing.crm.instagram.ai

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import pl.detailing.crm.instagram.ai.ratelimit.InstagramAiRateLimitExceededException
import pl.detailing.crm.instagram.ai.ratelimit.InstagramAiRateLimiter
import pl.detailing.crm.shared.StudioId

/**
 * Limit generowań AI per studio.
 *
 * Filtr per-IP nie chronił tego endpointu przed zalogowanym użytkownikiem jednego studia:
 * mieścił się w ogólnym limicie API i mógł w kilka minut wybić budżet OpenAI. Te testy
 * pilnują, że licznik jest kluczowany studiem, a oba okna działają niezależnie.
 */
class InstagramAiRateLimiterTest {

    private val redisTemplate = mockk<StringRedisTemplate>()
    private val valueOps = mockk<ValueOperations<String, String>>()
    private val registry = SimpleMeterRegistry()

    /** Prosty licznik w pamięci zamiast Redisa — INCR jest jedyną semantyką, która tu liczy. */
    private val counters = mutableMapOf<String, Long>()

    @BeforeEach
    fun setUp() {
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.increment(any()) } answers {
            val key = firstArg<String>()
            counters.merge(key, 1L, Long::plus)
        }
        every { redisTemplate.expire(any(), any()) } returns true
        every { redisTemplate.getExpire(any<String>()) } returns 1800L
    }

    private fun limiter(perHour: Int = 10, perDay: Int = 40) =
        InstagramAiRateLimiter(redisTemplate, registry, perHour, perDay)

    @Test
    fun `jedenaste zadanie w godzinie jest odrzucane`() {
        val limiter = limiter(perHour = 10, perDay = 40)
        val studio = StudioId.random()

        repeat(10) { limiter.checkAndConsume(studio) }

        val ex = assertThrows(InstagramAiRateLimitExceededException::class.java) {
            limiter.checkAndConsume(studio)
        }
        assertEquals("hour", ex.window)
        assertEquals(10, ex.limit)
        assertEquals(
            1.0,
            registry.get(InstagramAiRateLimiter.METRIC_RATE_LIMITED).tag("window", "hour").counter().count()
        )
    }

    @Test
    fun `okno dzienne dziala niezaleznie od godzinowego`() {
        val limiter = limiter(perHour = 1000, perDay = 3)
        val studio = StudioId.random()

        repeat(3) { limiter.checkAndConsume(studio) }

        val ex = assertThrows(InstagramAiRateLimitExceededException::class.java) {
            limiter.checkAndConsume(studio)
        }
        assertEquals("day", ex.window, "Limit godzinowy jeszcze się nie wyczerpał — blokuje dzienny")
    }

    @Test
    fun `kazde studio ma wlasny licznik`() {
        val limiter = limiter(perHour = 2, perDay = 40)
        val first = StudioId.random()
        val second = StudioId.random()

        repeat(2) { limiter.checkAndConsume(first) }
        assertThrows(InstagramAiRateLimitExceededException::class.java) { limiter.checkAndConsume(first) }

        val status = limiter.checkAndConsume(second)
        assertEquals(1L, status.remaining, "Wyczerpany limit sąsiada nie może blokować innego studia")
    }
}
