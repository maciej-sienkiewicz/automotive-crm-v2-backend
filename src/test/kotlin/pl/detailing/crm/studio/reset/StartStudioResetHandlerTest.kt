package pl.detailing.crm.studio.reset

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.password.PasswordEncoder
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UnauthorizedException
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import java.util.Optional
import java.util.UUID

class StartStudioResetHandlerTest {

    private val userRepository = mockk<UserRepository>()
    private val passwordEncoder = mockk<PasswordEncoder>()
    private val studioRepository = mockk<StudioRepository>()
    private val studioSettingsRepository = mockk<StudioSettingsRepository>()
    private val jobRepository = mockk<StudioResetJobRepository>()
    private val auditService = mockk<AuditService>()

    private val handler = StartStudioResetHandler(
        userRepository, passwordEncoder, studioRepository,
        studioSettingsRepository, jobRepository, auditService
    )

    private val studioId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val principal = UserPrincipal(
        userId = UserId(userId),
        studioId = StudioId(studioId),
        isOwner = true,
        email = "owner@studio.pl",
        fullName = "Jan Właściciel",
        phoneNumber = "+48123456789"
    )

    private val user = mockk<UserEntity> {
        every { passwordHash } returns "\$2a\$12\$hash"
    }

    private val settings = mockk<StudioSettingsEntity> {
        every { name } returns "Auto Spa Kraków"
    }

    @BeforeEach
    fun setUp() {
        every { userRepository.findByIdAndStudioId(userId, studioId) } returns user
        every { passwordEncoder.matches("dobre-haslo", any()) } returns true
        every { passwordEncoder.matches(neq("dobre-haslo"), any()) } returns false
        every { studioSettingsRepository.findById(studioId) } returns Optional.of(settings)
        every { jobRepository.findActiveByStudioId(studioId) } returns null
        every { jobRepository.save(any()) } answers { firstArg() }
        every { auditService.logSync(any()) } just Runs
    }

    private fun command(
        password: String = "dobre-haslo",
        confirmationName: String = "Auto Spa Kraków",
        wipeCompanyData: Boolean = false
    ) = StartStudioResetCommand(principal, password, confirmationName, wipeCompanyData)

    @Test
    fun `bledne haslo konczy sie 401 i nie tworzy joba`() = runTest {
        assertThrows<UnauthorizedException> { handler.handle(command(password = "zle-haslo")) }
        verify(exactly = 0) { jobRepository.save(any()) }
        verify(exactly = 0) { auditService.logSync(any()) }
    }

    @Test
    fun `bledna nazwa firmy konczy sie bledem walidacji`() = runTest {
        assertThrows<ValidationException> { handler.handle(command(confirmationName = "Inna Nazwa")) }
        verify(exactly = 0) { jobRepository.save(any()) }
    }

    @Test
    fun `nazwa firmy jest porownywana po przycieciu bialych znakow`() = runTest {
        val job = handler.handle(command(confirmationName = "  Auto Spa Kraków  "))
        assertEquals(StudioResetJobStatus.PENDING, job.status)
    }

    @Test
    fun `gdy ustawienia nie maja nazwy, obowiazuje nazwa studia`() = runTest {
        every { studioSettingsRepository.findById(studioId) } returns Optional.empty()
        every { studioRepository.findByStudioId(studioId) } returns mockk {
            every { name } returns "Studio Bez Ustawień"
        }

        val job = handler.handle(command(confirmationName = "Studio Bez Ustawień"))
        assertEquals(StudioResetJobStatus.PENDING, job.status)
    }

    @Test
    fun `trwajacy reset blokuje kolejne zlecenie`() = runTest {
        every { jobRepository.findActiveByStudioId(studioId) } returns mockk()
        assertThrows<ConflictException> { handler.handle(command()) }
        verify(exactly = 0) { jobRepository.save(any()) }
    }

    @Test
    fun `poprawne zlecenie tworzy job PENDING z wlascicielem jako ocalonym kontem`() = runTest {
        val job = handler.handle(command(wipeCompanyData = true))

        assertEquals(StudioResetJobStatus.PENDING, job.status)
        assertEquals(studioId, job.studioId)
        assertEquals(userId, job.requestedBy)
        assertEquals("Jan Właściciel", job.requestedByName)
        assertTrue(job.wipeCompanyData)
        assertEquals(0, job.currentStep)
    }

    @Test
    fun `zlecenie zostawia krytyczny wpis audytowy przed rozpoczeciem purge`() = runTest {
        val audited = slot<LogAuditCommand>()
        every { auditService.logSync(capture(audited)) } just Runs

        val job = handler.handle(command())

        assertEquals(AuditAction.ACCOUNT_RESET_STARTED, audited.captured.action)
        assertEquals(studioId, audited.captured.studioId.value)
        assertEquals(job.id.toString(), audited.captured.metadata["jobId"])
        assertEquals("false", audited.captured.metadata["wipeCompanyData"])
    }

    @Test
    fun `domyslnie dane firmy nie sa czyszczone`() = runTest {
        val job = handler.handle(command())
        assertFalse(job.wipeCompanyData)
    }
}
