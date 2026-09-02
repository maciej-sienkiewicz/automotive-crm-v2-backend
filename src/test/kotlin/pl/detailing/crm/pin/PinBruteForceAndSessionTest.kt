package pl.detailing.crm.pin

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import pl.detailing.crm.auth.UnifiedAuthResponse
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UnauthorizedException
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.subscription.SubscriptionService
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.voice.MobileTokenService
import java.util.UUID

/**
 * Privilege Escalation — przełączanie na konto właściciela 4-cyfrowym PIN-em.
 *
 * Luka 1: licznik nieudanych prób był odczytywany i zapisywany na wierszu `users`
 *   (read-modify-write) — N równoległych żądań widziało 0 i każde zapisywało 1, więc
 *   blokada po 3 próbach nigdy nie łapała ataku równoległego. Teraz licznik to atomowy
 *   `INCR` w Redisie: trzecia próba blokuje, niezależnie od współbieżności.
 * Luka 2: udane przełączenie nie zmieniało identyfikatora sesji (session fixation).
 */
class PinBruteForceAndSessionTest {

    private val userRepository = mockk<UserRepository>()
    private val passwordEncoder = mockk<PasswordEncoder>()
    private val redis = mockk<StringRedisTemplate>(relaxed = true)
    private val valueOps = mockk<ValueOperations<String, String>>()

    private val studioId = UUID.randomUUID()
    private val owner = UserEntity(
        id = UUID.randomUUID(), studioId = studioId, email = "owner@studio.pl", phoneNumber = "",
        passwordHash = "x", firstName = "Owner", lastName = "One", isOwner = true
    ).apply { pinHash = "hash" }

    private val studioSettingsRepository = mockk<StudioSettingsRepository>(relaxed = true)

    private val handler = SwitchUserViaPinHandler(
        userRepository, passwordEncoder,
        mockk<SubscriptionService>(relaxed = true),
        mockk<PermissionCheckService>(relaxed = true),
        studioSettingsRepository,
        redis
    )

    init {
        every { userRepository.findByIdAndStudioId(owner.id, studioId) } returns owner
        every { userRepository.save(any()) } answers { firstArg() }
        every { redis.opsForValue() } returns valueOps
        every { passwordEncoder.matches("0000", "hash") } returns false
        every { studioSettingsRepository.findById(any()) } returns java.util.Optional.empty()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `the atomic counter, not the user row, decides the lock - third concurrent guess locks the PIN`() = runBlocking {
        // Simulate three racing requests: every one of them sees pinFailedAttempts == 0 on the
        // entity, but Redis INCR hands out 1, 2, 3.
        every { valueOps.increment(pinAttemptsKey(studioId, owner.id)) } returnsMany listOf(1L, 2L, 3L)

        assertThrows<UnauthorizedException> { handler.handle(owner.id, studioId, "0000") }
        owner.pinFailedAttempts = 0 // stale read, as in the race
        assertThrows<UnauthorizedException> { handler.handle(owner.id, studioId, "0000") }
        owner.pinFailedAttempts = 0
        assertThrows<ForbiddenException> { handler.handle(owner.id, studioId, "0000") }

        assertTrue(owner.pinLocked, "PIN must be locked after the 3rd failure regardless of stale entity state")
        verify(exactly = 3) { valueOps.increment(pinAttemptsKey(studioId, owner.id)) }
        // First failure arms the window TTL
        verify(exactly = 1) { redis.expire(pinAttemptsKey(studioId, owner.id), any()) }
    }

    @Test
    fun `a locked PIN is refused before the hash is even compared`() = runBlocking {
        owner.pinLocked = true
        assertThrows<ForbiddenException> { handler.handle(owner.id, studioId, "1234") }
        verify(exactly = 0) { passwordEncoder.matches(any(), any()) }
        verify(exactly = 0) { valueOps.increment(any()) }
    }

    @Test
    fun `successful switch clears the counter`() = runBlocking {
        every { passwordEncoder.matches("1234", "hash") } returns true
        handler.handle(owner.id, studioId, "1234")
        verify(exactly = 1) { redis.delete(pinAttemptsKey(studioId, owner.id)) }
        Unit
    }

    @Test
    fun `switching identity rotates the HTTP session id (session fixation)`() {
        val switchHandler = mockk<SwitchUserViaPinHandler>()
        val newPrincipal = UserPrincipal(UserId(owner.id), StudioId(studioId), true, owner.email, "Owner One", "")
        coEvery { switchHandler.handle(any(), any(), any()) } returns Pair(UnifiedAuthResponse(success = true), newPrincipal)

        val controller = PinController(
            mockk<SetPinHandler>(relaxed = true), switchHandler, userRepository,
            mockk<SubscriptionService>(relaxed = true), mockk<SecurityContextRepository>(relaxed = true),
            mockk<MobileTokenService>(relaxed = true), mockk<PermissionCheckService>(relaxed = true), redis
        )
        val mockMvc = MockMvcBuilders.standaloneSetup(controller).build()

        SecurityContextHolder.setContext(SecurityContextImpl(
            UserPrincipal(UserId.random(), StudioId(studioId), false, "emp@studio.pl", "Emp", "")
        ))
        val session = MockHttpSession()
        val idBefore = session.id

        val result = mockMvc.perform(
            post("/api/v1/pin/switch").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"${owner.id}","pin":"1234"}""")
        ).andExpect(status().isOk).andReturn()

        assertNotEquals(idBefore, result.request.session!!.id, "session id must change when the identity changes")
    }
}
