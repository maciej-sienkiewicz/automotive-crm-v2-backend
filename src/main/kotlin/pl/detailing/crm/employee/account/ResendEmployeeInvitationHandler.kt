package pl.detailing.crm.employee.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.auth.passwordreset.PasswordResetProperties
import pl.detailing.crm.auth.passwordreset.PasswordResetTokenService
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Duration
import java.time.Instant

/**
 * „Wyślij maila ponownie" na karcie pracownika: nowy link do ustawienia hasła dla konta,
 * którego pracownik jeszcze nie aktywował (link wygasł, e-mail nie doszedł albo zginął
 * w skrzynce). Konta już aktywnego nie dotyczy - tam hasło resetuje się na ekranie logowania.
 */
@Service
class ResendEmployeeInvitationHandler(
    private val employeeRepository: EmployeeRepository,
    private val userRepository: UserRepository,
    private val tokenService: PasswordResetTokenService,
    private val emailProvider: EmailProvider,
    private val auditService: AuditService,
    private val properties: PasswordResetProperties
) {
    companion object {
        /** Dwa kliknięcia pod rząd nie mogą wysłać pracownikowi dwóch e-maili. */
        val RESEND_COOLDOWN: Duration = Duration.ofMinutes(1)
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun handle(
        studioId: StudioId,
        employeeId: EmployeeId,
        requestedBy: UserId,
        requestedByName: String?,
        now: Instant = Instant.now()
    ): ResendInvitationResult = withContext(Dispatchers.IO) {
        val employee = employeeRepository.findByIdAndStudioId(employeeId.value, studioId.value)
            ?: throw EntityNotFoundException("Pracownik nie istnieje")
        val userId = employee.userId
            ?: throw ValidationException("Pracownik nie ma konta - utwórz je, a zaproszenie wyśle się samo")
        val user = userRepository.findByIdAndStudioId(userId, studioId.value)
            ?: throw EntityNotFoundException("Konto użytkownika nie istnieje")

        if (!user.invitationPending) {
            throw ConflictException(
                "Pracownik już aktywował konto. Jeśli nie pamięta hasła, zresetuje je na ekranie logowania."
            )
        }
        if (!user.isActive) {
            throw ConflictException("Konto jest zablokowane - najpierw je aktywuj, a potem wyślij zaproszenie")
        }
        user.invitationSentAt?.let { lastSent ->
            if (Duration.between(lastSent, now) < RESEND_COOLDOWN) {
                throw ConflictException("Zaproszenie wysłano przed chwilą - spróbuj ponownie za minutę")
            }
        }

        val rawToken = tokenService.issueInvitationToken(user.id)
        val result = emailProvider.send(
            to = user.email,
            subject = EmployeeInvitationEmail.SUBJECT,
            bodyText = EmployeeInvitationEmail.body(
                firstName = employee.firstName,
                invitedByName = requestedByName,
                setupLink = EmployeeInvitationEmail.setupLink(properties, rawToken),
                validFor = EmployeeInvitationEmail.hoursInPolish(properties.invitationTokenTtlHours)
            )
        )
        if (!result.success) {
            logger.warn(
                "Invitation re-send failed [employeeId={}, userId={}]: {}",
                employeeId.value, user.id, result.errorMessage
            )
            throw UnprocessableEntityException(
                "Nie udało się wysłać zaproszenia na ${user.email}. Sprawdź adres i spróbuj ponownie."
            )
        }

        userRepository.markInvitationSent(user.id, now)
        logger.info("Invitation re-sent [employeeId={}, userId={}]", employeeId.value, user.id)

        auditService.log(LogAuditCommand(
            studioId = studioId,
            userId = requestedBy,
            userDisplayName = requestedByName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = employeeId.value.toString(),
            entityDisplayName = "${employee.firstName} ${employee.lastName}",
            action = AuditAction.STATUS_CHANGE,
            changes = listOf(FieldChange("invitation", null, "resent"))
        ))

        ResendInvitationResult(
            sentAt = now,
            expiresAt = now.plus(Duration.ofHours(properties.invitationTokenTtlHours))
        )
    }
}

data class ResendInvitationResult(
    val sentAt: Instant,
    val expiresAt: Instant
)
