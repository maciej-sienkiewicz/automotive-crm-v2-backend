package pl.detailing.crm.auth.passwordreset

import pl.detailing.crm.rolepreview.RolePreviewStudios
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.auth.PasswordPolicy
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import java.util.Optional
import java.util.UUID

private class ImmediateTransactionManager : PlatformTransactionManager {
    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
    override fun commit(status: TransactionStatus) = Unit
    override fun rollback(status: TransactionStatus) = Unit
}

/**
 * Hasło ustawione z linku zaproszenia aktywuje konto pracownika - karta pracownika
 * przestaje pokazywać „Czeka na aktywację".
 */
class ResetPasswordActivatesInvitationTest {

    @Test
    fun `haslo ustawione z zaproszenia konczy oczekiwanie na aktywacje`() = runBlocking {
        val userId = UUID.randomUUID()
        val user = UserEntity(
            id = userId, studioId = UUID.randomUUID(), email = "anna.nowak@example.com", phoneNumber = "",
            passwordHash = "random", firstName = "Anna", lastName = "Nowak", isOwner = false,
            invitationPending = true
        )
        val userRepository = mockk<UserRepository>()
        val tokenService = mockk<PasswordResetTokenService>()
        val passwordEncoder = mockk<PasswordEncoder>()
        val redisTemplate = mockk<StringRedisTemplate>(relaxed = true)
        val saved = slot<UserEntity>()
        every { tokenService.consumeToken("INVITE") } returns userId
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { userRepository.save(capture(saved)) } answers { firstArg() }
        every { passwordEncoder.encode("Haslo123!") } returns "bcrypt"

        ResetPasswordHandler(
            userRepository, tokenService, passwordEncoder, mockk<PasswordPolicy>(relaxed = true),
            redisTemplate, TransactionTemplate(ImmediateTransactionManager()), regularStudios()
        ).handle(ResetPasswordRequest(token = "INVITE", password = "Haslo123!", confirmPassword = "Haslo123!"))

        assertEquals("bcrypt", saved.captured.passwordHash)
        assertFalse(saved.captured.invitationPending)
    }
}

/** Zwykłe studia - żadne nie jest piaskownicą podglądu roli. */
private fun regularStudios(): RolePreviewStudios =
    mockk { every { isRolePreview(any()) } returns false }
