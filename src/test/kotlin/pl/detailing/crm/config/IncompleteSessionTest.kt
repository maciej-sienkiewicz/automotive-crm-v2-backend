package pl.detailing.crm.config

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.RedisOperations
import org.springframework.session.SessionRepository
import org.springframework.session.data.redis.RedisSessionRepository

/**
 * Sesja usunięta w trakcie żądania wraca do Redisa jako niepełny wpis (same zmienione
 * atrybuty). Kolejne żądanie z tym ciasteczkiem ma być zwykłym „niezalogowany", a nie
 * błędem 500 przy każdym żądaniu aż do wygaśnięcia wpisu.
 */
class IncompleteSessionTest {

    private val redis = mockk<RedisOperations<String, Any>>()
    private val hash = mockk<HashOperations<String, String, Any>>()
    // Przez interfejs: klasa sesji w RedisSessionRepository nie jest publiczna.
    private val repository: SessionRepository<*> = RedisSessionRepository(redis).also {
        SecurityConfig("local").incompleteSessionIsNoSession().customize(it)
    }

    init {
        every { redis.opsForHash<String, Any>() } returns hash
        every { redis.delete(any<String>()) } returns true
    }

    @Test
    fun `niepelny wpis to brak sesji i znika z Redisa`() {
        every { hash.entries(any()) } returns mapOf("lastAccessedTime" to System.currentTimeMillis())

        assertNull(repository.findById("usunieta-w-trakcie-zadania"))

        verify(exactly = 1) { redis.delete(any<String>()) }
    }

    @Test
    fun `pelny wpis jest zwykla sesja`() {
        val now = System.currentTimeMillis()
        // Typy jak w Redisie: czasy jako Long, limit bezczynności jako Integer.
        every { hash.entries(any()) } returns mapOf<String, Any>(
            "creationTime" to now,
            "lastAccessedTime" to now,
            "maxInactiveInterval" to Integer.valueOf(604800)
        )

        assertEquals("zywa", repository.findById("zywa")?.id)
        verify(exactly = 0) { redis.delete(any<String>()) }
    }
}
