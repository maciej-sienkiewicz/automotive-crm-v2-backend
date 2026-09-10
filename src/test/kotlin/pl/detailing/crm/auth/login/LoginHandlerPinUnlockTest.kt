package pl.detailing.crm.auth.login

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import pl.detailing.crm.pin.pinAttemptsKey
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.subscription.SubscriptionInfo
import pl.detailing.crm.subscription.SubscriptionService
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import java.util.Optional
import java.util.UUID

/**
 * Logowanie hasłem to ścieżka odzyskania, na którą przełącznik profili kieruje
 * zablokowanego użytkownika. Test pinuje, że udane logowanie zdejmuje blokadę PIN
 * i kasuje licznik prób w Redisie — a zwykłe logowanie nie generuje dodatkowego
 * zapisu ani nie rusza Redisa.
 */
class LoginHandlerPinUnlockTest {

    private val userRepository = mockk<UserRepository>()
    private val passwordEncoder = mockk<PasswordEncoder>()
    private val subscriptionService = mockk<SubscriptionService>()
    private val accountLockoutService = mockk<AccountLockoutService>()
    private val permissionCheckService = mockk<PermissionCheckService>()
    private val studioSettingsRepository = mockk<StudioSettingsRepository>()
    private val redisTemplate = mockk<StringRedisTemplate>()

    private val handler = LoginHandler(
        userRepository, passwordEncoder, subscriptionService, accountLockoutService,
        SimpleMeterRegistry(), permissionCheckService, studioSettingsRepository, redisTemplate
    )

    private val studioId = UUID.randomUUID()
    private val userId = UUID.randomUUID()

    private fun user(pinLocked: Boolean, pinFailedAttempts: Int) = UserEntity(
        id = userId,
        studioId = studioId,
        email = "pracownik@firma.pl",
        phoneNumber = "+48600100200",
        passwordHash = "hash",
        firstName = "Anna",
        lastName = "Nowak",
        isOwner = false,
        pinLocked = pinLocked,
        pinFailedAttempts = pinFailedAttempts
    )

    private fun stubHappyPath() {
        every { accountLockoutService.isLocked(any()) } returns false
        every { accountLockoutService.clear(any()) } just Runs
        every { passwordEncoder.matches("dobre-haslo", "hash") } returns true
        coEvery { subscriptionService.getSubscriptionInfo(any()) } returns SubscriptionInfo(
            status = SubscriptionStatus.ACTIVE, daysRemaining = 10, subscriptionEndsAt = null,
            trialEndsAt = null, isAccessible = true, trialUsed = false
        )
        every { permissionCheckService.getPermissions(any(), any()) } returns null
        every { permissionCheckService.getTrackWorkTime(any(), any()) } returns false
        every { studioSettingsRepository.findById(studioId) } returns Optional.empty()
    }

    @Test
    fun `logowanie haslem zdejmuje blokade PIN i kasuje licznik prob`() = runBlocking {
        val entity = user(pinLocked = true, pinFailedAttempts = 3)
        stubHappyPath()
        every { userRepository.findByEmail("pracownik@firma.pl") } returns entity
        every { userRepository.save(any<UserEntity>()) } answers { firstArg() }
        every { redisTemplate.delete(any<String>()) } returns true

        handler.handle(LoginRequest("Pracownik@Firma.pl", "dobre-haslo"))

        assertFalse(entity.pinLocked)
        assertEquals(0, entity.pinFailedAttempts)
        verify(exactly = 1) { userRepository.save(entity) }
        verify(exactly = 1) { redisTemplate.delete(pinAttemptsKey(studioId, userId)) }
    }

    @Test
    fun `logowanie bez blokady nie robi dodatkowego zapisu ani nie rusza Redisa`() = runBlocking {
        val entity = user(pinLocked = false, pinFailedAttempts = 0)
        stubHappyPath()
        every { userRepository.findByEmail("pracownik@firma.pl") } returns entity

        handler.handle(LoginRequest("pracownik@firma.pl", "dobre-haslo"))

        verify(exactly = 0) { userRepository.save(any<UserEntity>()) }
        verify(exactly = 0) { redisTemplate.delete(any<String>()) }
    }
}
