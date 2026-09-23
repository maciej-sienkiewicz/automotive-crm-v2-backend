package pl.detailing.crm.auth.login

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.password.PasswordEncoder
import pl.detailing.crm.rolepreview.RolePreviewStudios
import pl.detailing.crm.shared.UnauthorizedException
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import java.util.UUID

/**
 * Konto piaskownicy podglądu roli nie loguje się niczym poza jednorazowym kodem wejścia.
 * Odmowa pada, zanim ktokolwiek porówna hasło - żadne hasło jej nie obchodzi.
 */
class LoginHandlerRolePreviewTest {

    private val userRepository = mockk<UserRepository>()
    private val passwordEncoder = mockk<PasswordEncoder>()
    private val accountLockoutService = mockk<AccountLockoutService>()
    private val studios = mockk<RolePreviewStudios>()

    private val handler = LoginHandler(
        userRepository, passwordEncoder, mockk(), accountLockoutService,
        SimpleMeterRegistry(), mockk(), mockk(), mockk(), studios
    )

    @Test
    fun `konto piaskownicy nie loguje sie nawet pasujacym haslem`() {
        val sandboxStudio = UUID.randomUUID()
        val email = "pracownik-abc@podglad.invalid"
        every { accountLockoutService.isLocked(email) } returns false
        every { userRepository.findByEmail(email) } returns UserEntity(
            id = UUID.randomUUID(),
            studioId = sandboxStudio,
            email = email,
            phoneNumber = "+48000000000",
            passwordHash = "hash",
            firstName = "Pracownik",
            lastName = "Podglądowy",
            isOwner = false
        )
        every { studios.isRolePreview(sandboxStudio) } returns true
        every { passwordEncoder.matches(any(), any()) } returns true

        assertThrows<UnauthorizedException> {
            runBlocking { handler.handle(LoginRequest(email, "pasujace-haslo")) }
        }

        verify(exactly = 0) { passwordEncoder.matches(any(), any()) }
    }
}
