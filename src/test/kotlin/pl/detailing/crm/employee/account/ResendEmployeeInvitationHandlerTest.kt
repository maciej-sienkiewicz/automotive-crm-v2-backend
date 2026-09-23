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
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * „Wyślij maila ponownie" na karcie pracownika: nowy link do ustawienia hasła - tylko dla
 * konta, którego pracownik jeszcze nie aktywował.
 */
class ResendEmployeeInvitationHandlerTest {

    private val studioId = UUID.randomUUID()
    private val employeeId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val now = Instant.parse("2026-09-23T10:00:00Z")

    private val employee = EmployeeEntity(
        id = employeeId,
        studioId = studioId,
        userId = userId,
        firstName = "Anna",
        lastName = "Nowak",
        phone = "+48600100200",
        email = null,
        createdBy = UUID.randomUUID(),
        updatedBy = UUID.randomUUID()
    )

    private fun account(pending: Boolean = true, active: Boolean = true, sentAt: Instant? = now.minus(Duration.ofDays(3))) =
        UserEntity(
            id = userId,
            studioId = studioId,
            email = "anna.nowak@example.com",
            phoneNumber = "",
            passwordHash = "hash",
            firstName = "Anna",
            lastName = "Nowak",
            isOwner = false,
            isActive = active,
            invitationPending = pending,
            invitationSentAt = sentAt
        )

    private val employeeRepository = mockk<EmployeeRepository>()
    private val userRepository = mockk<UserRepository>()
    private val tokenService = mockk<PasswordResetTokenService>()
    private val emailProvider = mockk<EmailProvider>()
    private val auditService = mockk<AuditService>()
    private val sentBody = slot<String>()

    private val handler = ResendEmployeeInvitationHandler(
        employeeRepository, userRepository, tokenService, emailProvider, auditService, PasswordResetProperties(),
        rolePreviewGuard = mockk(relaxed = true)
    )

    init {
        every { employeeRepository.findByIdAndStudioId(employeeId, studioId) } returns employee
        every { tokenService.issueInvitationToken(userId) } returns "NEW-TOKEN"
        every { emailProvider.send(any(), any(), capture(sentBody), any()) } returns
            EmailDeliveryResult(success = true, messageId = "m1", errorMessage = null)
        every { userRepository.markInvitationSent(any(), any()) } returns 1
        coJustRun { auditService.log(any()) }
    }

    private fun resend() = runBlocking {
        handler.handle(StudioId(studioId), EmployeeId(employeeId), UserId(UUID.randomUUID()), "Jan Kowalski", now)
    }

    @Test
    fun `wysyla nowy link 48h na login konta i zapisuje godzine wysylki`() {
        every { userRepository.findByIdAndStudioId(userId, studioId) } returns account()

        val result = resend()

        verify(exactly = 1) { emailProvider.send("anna.nowak@example.com", EmployeeInvitationEmail.SUBJECT, any(), any()) }
        assertTrue("https://detailboost.pl/confirm-password?token=NEW-TOKEN" in sentBody.captured, sentBody.captured)
        verify(exactly = 1) { userRepository.markInvitationSent(userId, now) }
        assertEquals(now, result.sentAt)
        assertEquals(now.plus(Duration.ofHours(48)), result.expiresAt)
    }

    @Test
    fun `konto juz aktywowane - nie wysyla zaproszenia`() {
        every { userRepository.findByIdAndStudioId(userId, studioId) } returns account(pending = false)

        assertThrows<ConflictException> { resend() }
        verify(exactly = 0) { emailProvider.send(any(), any(), any(), any()) }
    }

    @Test
    fun `konto zablokowane - najpierw trzeba je aktywowac`() {
        every { userRepository.findByIdAndStudioId(userId, studioId) } returns account(active = false)

        assertThrows<ConflictException> { resend() }
        verify(exactly = 0) { tokenService.issueInvitationToken(any()) }
    }

    @Test
    fun `drugie klikniecie w ciagu minuty nie wysyla drugiego maila`() {
        every { userRepository.findByIdAndStudioId(userId, studioId) } returns account(sentAt = now.minusSeconds(20))

        assertThrows<ConflictException> { resend() }
        verify(exactly = 0) { emailProvider.send(any(), any(), any(), any()) }
    }

    @Test
    fun `nieudana wysylka konczy sie bledem i nie udaje, ze zaproszenie doszlo`() {
        every { userRepository.findByIdAndStudioId(userId, studioId) } returns account(sentAt = null)
        every { emailProvider.send(any(), any(), any(), any()) } returns
            EmailDeliveryResult(success = false, messageId = null, errorMessage = "bounce")

        assertThrows<UnprocessableEntityException> { resend() }
        verify(exactly = 0) { userRepository.markInvitationSent(any(), any()) }
    }
}
