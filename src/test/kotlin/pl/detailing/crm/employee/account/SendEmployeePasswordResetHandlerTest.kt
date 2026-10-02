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
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.auth.passwordreset.PasswordResetProperties
import pl.detailing.crm.auth.passwordreset.PasswordResetTokenService
import pl.detailing.crm.email.provider.EmailDeliveryResult
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.employee.infrastructure.EmployeeEntity
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.EmployeeId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UnprocessableEntityException
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * „Resetuj hasło" w oknie pracownika: link do ustawienia nowego hasła idzie na login
 * konta. Administrator hasła nie zna i nie wpisuje.
 */
class SendEmployeePasswordResetHandlerTest {

    private val studioId = UUID.randomUUID()
    private val employeeId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val now = Instant.parse("2026-10-02T10:00:00Z")

    private fun employee(withAccount: Boolean = true) = EmployeeEntity(
        id = employeeId,
        studioId = studioId,
        userId = if (withAccount) userId else null,
        firstName = "Anna",
        lastName = "Nowak",
        phone = "+48600100200",
        email = null,
        createdBy = UUID.randomUUID(),
        updatedBy = UUID.randomUUID()
    )

    private fun account(pending: Boolean = false, active: Boolean = true) = UserEntity(
        id = userId,
        studioId = studioId,
        email = "anna.nowak@example.com",
        phoneNumber = "",
        passwordHash = "hash",
        firstName = "Anna",
        lastName = "Nowak",
        isOwner = false,
        isActive = active,
        invitationPending = pending
    )

    private val employeeRepository = mockk<EmployeeRepository>()
    private val userRepository = mockk<UserRepository>()
    private val tokenService = mockk<PasswordResetTokenService>()
    private val emailProvider = mockk<EmailProvider>()
    private val auditService = mockk<AuditService>()
    private val sentBody = slot<String>()

    private val handler = SendEmployeePasswordResetHandler(
        employeeRepository, userRepository, tokenService, emailProvider, auditService, PasswordResetProperties(),
        rolePreviewGuard = mockk(relaxed = true)
    )

    init {
        every { employeeRepository.findByIdAndStudioId(employeeId, studioId) } returns employee()
        every { tokenService.tryStartCooldown(any()) } returns true
        every { tokenService.issueToken(userId) } returns "RESET-TOKEN"
        every { emailProvider.send(any(), any(), capture(sentBody), any()) } returns
            EmailDeliveryResult(success = true, messageId = "m1", errorMessage = null)
        coJustRun { auditService.log(any()) }
    }

    private fun send() = runBlocking {
        handler.handle(StudioId(studioId), EmployeeId(employeeId), UserId(UUID.randomUUID()), "Jan Kowalski", now)
    }

    @Test
    fun `wysyla link do ekranu resetu hasla na login konta`() {
        every { userRepository.findByIdAndStudioId(userId, studioId) } returns account()

        val result = send()

        verify(exactly = 1) { emailProvider.send("anna.nowak@example.com", SendEmployeePasswordResetHandler.SUBJECT, any(), any()) }
        assertTrue("https://detailboost.pl/reset-password?token=RESET-TOKEN" in sentBody.captured, sentBody.captured)
        assertTrue("Jan Kowalski" in sentBody.captured, "mail mówi, kto poprosił o zmianę hasła")
        assertEquals("anna.nowak@example.com", result.email)
        assertEquals(now, result.sentAt)
        assertEquals(now.plus(Duration.ofMinutes(30)), result.expiresAt)
    }

    @Test
    fun `pracownik bez konta - nie ma czego resetowac`() {
        every { employeeRepository.findByIdAndStudioId(employeeId, studioId) } returns employee(withAccount = false)

        assertThrows<ValidationException> { send() }
        verify(exactly = 0) { emailProvider.send(any(), any(), any(), any()) }
    }

    @Test
    fun `konto czekajace na aktywacje dostaje zaproszenie, nie reset`() {
        every { userRepository.findByIdAndStudioId(userId, studioId) } returns account(pending = true)

        assertThrows<ConflictException> { send() }
        verify(exactly = 0) { tokenService.issueToken(any()) }
    }

    @Test
    fun `konto zablokowane - najpierw trzeba je odblokowac`() {
        every { userRepository.findByIdAndStudioId(userId, studioId) } returns account(active = false)

        assertThrows<ConflictException> { send() }
        verify(exactly = 0) { emailProvider.send(any(), any(), any(), any()) }
    }

    @Test
    fun `drugie klikniecie w ciagu minuty nie wysyla drugiego maila`() {
        every { userRepository.findByIdAndStudioId(userId, studioId) } returns account()
        every { tokenService.tryStartCooldown("anna.nowak@example.com") } returns false

        assertThrows<ConflictException> { send() }
        verify(exactly = 0) { tokenService.issueToken(any()) }
    }

    @Test
    fun `nieudana wysylka konczy sie bledem i nie trafia do audytu`() {
        every { userRepository.findByIdAndStudioId(userId, studioId) } returns account()
        every { emailProvider.send(any(), any(), any(), any()) } returns
            EmailDeliveryResult(success = false, messageId = null, errorMessage = "bounce")

        assertThrows<UnprocessableEntityException> { send() }
        io.mockk.coVerify(exactly = 0) { auditService.log(any()) }
    }
}
