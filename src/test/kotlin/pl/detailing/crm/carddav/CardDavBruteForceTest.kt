package pl.detailing.crm.carddav

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.crypto.password.PasswordEncoder
import pl.detailing.crm.auth.login.AccountLockoutService
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import java.util.UUID

/**
 * Brute force przez boczne drzwi — HTTP Basic CardDAV weryfikował hasło konta bez
 * żadnego licznika, omijając blokadę z `/auth/login`. Teraz obie drogi dzielą jedną
 * blokadę [AccountLockoutService].
 */
class CardDavBruteForceTest {

    private val userRepository = mockk<UserRepository>()
    private val appPasswords = mockk<CardDavAppPasswordRepository>()
    private val encoder = mockk<PasswordEncoder>()
    private val lockout = mockk<AccountLockoutService>(relaxed = true)
    private val provider = CardDavAuthenticationProvider(userRepository, appPasswords, encoder, lockout)

    private val user = UserEntity(
        id = UUID.randomUUID(), studioId = UUID.randomUUID(), email = "owner@studio.pl", phoneNumber = "",
        passwordHash = "bcrypt", firstName = "O", lastName = "W", isOwner = true
    )

    init {
        every { userRepository.findByEmail("owner@studio.pl") } returns user
        every { appPasswords.findActiveByUserId(user.id) } returns emptyList()
    }

    @Test
    fun `wrong password is counted towards the shared lockout`() {
        every { lockout.isLocked("owner@studio.pl") } returns false
        every { encoder.matches("guess", "bcrypt") } returns false

        assertThrows<BadCredentialsException> {
            provider.authenticate(UsernamePasswordAuthenticationToken("owner@studio.pl", "guess"))
        }
        verify(exactly = 1) { lockout.recordFailure("owner@studio.pl") }
    }

    @Test
    fun `a locked account is refused without touching the password hash`() {
        every { lockout.isLocked("owner@studio.pl") } returns true

        assertThrows<BadCredentialsException> {
            provider.authenticate(UsernamePasswordAuthenticationToken("owner@studio.pl", "correct-password"))
        }
        verify(exactly = 0) { encoder.matches(any(), any()) }
    }

    @Test
    fun `correct password clears the counter`() {
        every { lockout.isLocked("owner@studio.pl") } returns false
        every { encoder.matches("correct", "bcrypt") } returns true

        provider.authenticate(UsernamePasswordAuthenticationToken("owner@studio.pl", "correct"))
        verify(exactly = 1) { lockout.clear("owner@studio.pl") }
    }
}
