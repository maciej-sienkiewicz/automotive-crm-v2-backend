package pl.detailing.crm.security

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

/**
 * „Przekroczono limit żądań" w module poczty: całe studio za jednym routerem dzieliło
 * jeden licznik IP. Zalogowany ruch liczy się teraz per użytkownik, a IP jest sufitem.
 */
class RateLimitFilterTest {

    /** Redis w pamięci: licznik na klucz, TTL stały na okno. */
    private val counters = mutableMapOf<String, Long>()
    private var redisDown = false
    private val redis: StringRedisTemplate = mockk {
        every { execute(any<RedisScript<List<*>>>(), any<List<String>>(), *anyVararg()) } answers {
            if (redisDown) throw IllegalStateException("redis down")
            val key = secondArg<List<String>>().single()
            val count = counters.merge(key, 1L, Long::plus)!!
            listOf(count, 42L)
        }
    }
    private val filter = RateLimitFilter(redis, SimpleMeterRegistry(), "")
    private val chain = FilterChain { _, _ -> }

    private fun request(path: String = "/api/comms/threads", ip: String = "10.0.0.5", user: UserId? = null) =
        MockHttpServletRequest("GET", path).apply {
            remoteAddr = ip
            if (user != null) {
                setSession(MockHttpSession().apply {
                    setAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                        SecurityContextImpl(
                            UserPrincipal(user, StudioId.random(), true, "o@studio.pl", "Anna Kowalska", "+48600000000")
                        )
                    )
                })
            }
        }

    private fun send(times: Int, make: () -> MockHttpServletRequest): MockHttpServletResponse {
        var last = MockHttpServletResponse()
        repeat(times) {
            last = MockHttpServletResponse()
            filter.doFilter(make(), last, chain)
        }
        return last
    }

    @Test
    fun `dwie osoby z jednego biura nie zjadaja sobie limitu`() {
        val anna = UserId.random()
        val tablet = UserId.random()

        send(RateLimitFilter.USER_API.limit) { request(user = anna) }
        val fromTablet = send(1) { request(user = tablet) }

        assertEquals(200, fromTablet.status)
        assertEquals(429, send(1) { request(user = anna) }.status, "własny limit użytkownika dalej działa")
    }

    @Test
    fun `zalogowany ma wyzszy limit niz dawny limit IP`() {
        val last = send(RateLimitFilter.API.limit + 1) { request(user = UserId.random()) }

        assertEquals(200, last.status)
    }

    @Test
    fun `ruch anonimowy liczy sie per IP jak dotad`() {
        send(RateLimitFilter.API.limit) { request() }

        assertEquals(429, send(1) { request() }.status)
        assertEquals(200, send(1) { request(ip = "10.0.0.6") }.status)
    }

    @Test
    fun `sesja bez zalogowanego uzytkownika nie daje osobnego licznika`() {
        send(RateLimitFilter.API.limit) { request() }
        val withEmptySession = request().apply { setSession(MockHttpSession()) }
        val response = MockHttpServletResponse()

        filter.doFilter(withEmptySession, response, chain)

        assertEquals(429, response.status)
    }

    @Test
    fun `sufit biura zatrzymuje zalew z jednego IP nawet przy wielu kontach`() {
        repeat(RateLimitFilter.OFFICE_API.limit) { counters.merge("ratelimit:office:10.0.0.5", 1L, Long::plus) }

        assertEquals(429, send(1) { request(user = UserId.random()) }.status)
    }

    @Test
    fun `odmowa mowi, po ilu sekundach sprobowac znowu`() {
        send(RateLimitFilter.API.limit) { request() }
        val rejected = send(1) { request() }

        assertEquals(429, rejected.status)
        assertEquals("42", rejected.getHeader("Retry-After"))
        assertTrue(rejected.contentAsString.contains("Przekroczono limit żądań"))
    }

    @Test
    fun `logowanie ma dalej wlasny, ciasny limit per IP`() {
        send(RateLimitFilter.LOGIN.limit) { request(path = "/api/auth/login", user = UserId.random()) }

        assertEquals(429, send(1) { request(path = "/api/auth/login") }.status)
    }

    @Test
    fun `awaria Redisa nie blokuje aplikacji`() {
        redisDown = true

        assertEquals(200, send(1) { request(user = UserId.random()) }.status)
    }
}
