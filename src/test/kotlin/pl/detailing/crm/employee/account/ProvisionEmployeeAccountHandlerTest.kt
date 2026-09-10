package pl.detailing.crm.employee.account

import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.auth.passwordreset.PasswordResetProperties
import pl.detailing.crm.auth.passwordreset.PasswordResetTokenService
import pl.detailing.crm.email.provider.EmailDeliveryResult
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.employee.infrastructure.EmployeeEntity
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.shared.EmployeeId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import java.util.UUID

/** Wykonuje callback synchronicznie — testujemy treść zaproszenia, nie transakcje. */
private class NoOpTransactionManager : PlatformTransactionManager {
    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
    override fun commit(status: TransactionStatus) = Unit
    override fun rollback(status: TransactionStatus) = Unit
}

/**
 * Zaproszenie pracownika ma dostać token z długim czasem życia (48 h), a e-mail ma
 * mówić prawdę o tym czasie. Wcześniej link dziedziczył 30 minut po resecie hasła
 * i wygasał, zanim pracownik zdążył otworzyć skrzynkę.
 */
class ProvisionEmployeeAccountHandlerTest {

    private val studioId = UUID.randomUUID()
    private val employeeId = UUID.randomUUID()
    private val employee = EmployeeEntity(
        id = employeeId,
        studioId = studioId,
        userId = null,
        firstName = "Anna",
        lastName = "Nowak",
        phone = "+48600100200",
        email = null,
        createdBy = UUID.randomUUID(),
        updatedBy = UUID.randomUUID()
    )

    private val employeeRepository = mockk<EmployeeRepository>()
    private val userRepository = mockk<UserRepository>()
    private val roleRepository = mockk<RoleRepository>()
    private val passwordEncoder = mockk<PasswordEncoder>()
    private val tokenService = mockk<PasswordResetTokenService>()
    private val emailProvider = mockk<EmailProvider>()
    private val auditService = mockk<AuditService>()

    private val sentBody = slot<String>()
    private val sentTo = slot<String>()

    private fun handler(properties: PasswordResetProperties = PasswordResetProperties()) = ProvisionEmployeeAccountHandler(
        employeeRepository, userRepository, roleRepository, passwordEncoder, tokenService,
        emailProvider, auditService, properties, TransactionTemplate(NoOpTransactionManager())
    )

    init {
        every { employeeRepository.findByIdAndStudioId(employeeId, studioId) } returns employee
        every { employeeRepository.save(any()) } answers { firstArg() }
        every { userRepository.existsByEmailAndStudioId(any(), studioId) } returns false
        every { userRepository.save(any<UserEntity>()) } answers { firstArg() }
        every { passwordEncoder.encode(any()) } returns "hash"
        every { tokenService.issueInvitationToken(any()) } returns "RAW-INVITE-TOKEN"
        every { emailProvider.send(capture(sentTo), any(), capture(sentBody), any()) } returns
            EmailDeliveryResult(success = true, messageId = "m1", errorMessage = null)
        coJustRun { auditService.log(any()) }
    }

    private fun command() = ProvisionEmployeeAccountCommand(
        studioId = StudioId(studioId),
        requestedBy = UserId(UUID.randomUUID()),
        requestedByName = "Jan Kowalski",
        employeeId = EmployeeId(employeeId),
        email = "Anna.Nowak@example.com"
    )

    @Test
    fun `zaproszenie dostaje token 48h, nie token resetu hasla`() = runBlocking {
        val userId = handler().handle(command())

        verify(exactly = 1) { tokenService.issueInvitationToken(userId.value) }
        verify(exactly = 0) { tokenService.issueToken(any()) }
        assertEquals(userId.value, employee.userId)
    }

    @Test
    fun `e-mail prowadzi do ekranu zakladania konta i mowi o 48 godzinach`() = runBlocking {
        handler().handle(command())

        assertEquals("anna.nowak@example.com", sentTo.captured)
        val body = sentBody.captured
        assertTrue("https://detailboost.pl/confirm-password?token=RAW-INVITE-TOKEN" in body, body)
        assertTrue("Link jest aktywny przez 48 godzin." in body, body)
        assertTrue("minut" !in body, "po zmianie nie ma już mowy o minutach: $body")
        assertTrue("Użytkownik Jan Kowalski zaprosił(-a) Cię" in body, body)
    }

    @Test
    fun `tresc e-maila podaza za konfiguracja czasu zycia`() = runBlocking {
        handler(PasswordResetProperties(invitationTokenTtlHours = 24)).handle(command())

        assertTrue("Link jest aktywny przez 24 godziny." in sentBody.captured, sentBody.captured)
    }

    @Test
    fun `odmiana godzin po polsku`() {
        val cases = mapOf(
            1L to "1 godzinę", 2L to "2 godziny", 4L to "4 godziny", 5L to "5 godzin",
            12L to "12 godzin", 22L to "22 godziny", 24L to "24 godziny", 48L to "48 godzin",
            72L to "72 godziny", 100L to "100 godzin"
        )
        cases.forEach { (hours, expected) ->
            assertEquals(expected, ProvisionEmployeeAccountHandler.hoursInPolish(hours))
        }
    }
}
