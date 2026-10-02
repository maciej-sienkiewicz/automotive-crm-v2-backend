package pl.detailing.crm.employee.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.auth.passwordreset.PasswordResetProperties
import pl.detailing.crm.auth.passwordreset.PasswordResetTokenService
import pl.detailing.crm.email.provider.EmailDeliveryResult
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.rolepreview.RolePreviewOutboundGuard
import pl.detailing.crm.rolepreview.SimulatedEffectChannel
import pl.detailing.crm.shared.*
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Duration
import java.time.Instant

/**
 * „Resetuj hasło" w ustawieniach okna pracownika: pracownik dostaje e-mail z linkiem do
 * ustawienia nowego hasła - ten sam link i ten sam ekran co „Nie pamiętam hasła".
 *
 * Administrator nie wpisuje już hasła za pracownika. Hasło, które zna ktoś drugi,
 * trzeba było potem przekazać (SMS-em, na kartce) i nikt go już nie zmieniał. Dotychczasowe
 * hasło działa, dopóki pracownik nie ustawi nowego, więc przypadkowe kliknięcie nikogo
 * nie wylogowuje.
 *
 * Konto, które czeka na aktywację, dostaje zaproszenie ([ResendEmployeeInvitationHandler]),
 * nie reset - link z zaproszenia żyje dłużej, a treść maila mówi, czym jest konto.
 */
@Service
class SendEmployeePasswordResetHandler(
    private val employeeRepository: EmployeeRepository,
    private val userRepository: UserRepository,
    private val tokenService: PasswordResetTokenService,
    private val emailProvider: EmailProvider,
    private val auditService: AuditService,
    private val properties: PasswordResetProperties,
    private val rolePreviewGuard: RolePreviewOutboundGuard
) {
    companion object {
        const val SUBJECT = "Ustaw nowe hasło – DetailBoost"
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun handle(
        studioId: StudioId,
        employeeId: EmployeeId,
        requestedBy: UserId,
        requestedByName: String?,
        now: Instant = Instant.now()
    ): PasswordResetSent = withContext(Dispatchers.IO) {
        val employee = employeeRepository.findByIdAndStudioId(employeeId.value, studioId.value)
            ?: throw EntityNotFoundException("Pracownik nie istnieje")
        val userId = employee.userId
            ?: throw ValidationException("Pracownik nie ma konta - najpierw je utwórz")
        val user = userRepository.findByIdAndStudioId(userId, studioId.value)
            ?: throw EntityNotFoundException("Konto użytkownika nie istnieje")

        if (!user.isActive) {
            throw ConflictException("Konto jest zablokowane - najpierw je odblokuj")
        }
        if (user.invitationPending) {
            throw ConflictException("Pracownik jeszcze nie aktywował konta - wyślij mu zaproszenie ponownie")
        }
        // Ta sama blokada co przy „Nie pamiętam hasła": dwa kliknięcia (albo administrator
        // i pracownik naraz) nie wyślą dwóch maili w ciągu minuty.
        if (!tokenService.tryStartCooldown(user.email)) {
            throw ConflictException("Link wysłano przed chwilą - spróbuj ponownie za minutę")
        }

        val rawToken = tokenService.issueToken(user.id)
        val link = "${properties.frontendBaseUrl.trimEnd('/')}/reset-password?token=$rawToken"
        val result = if (rolePreviewGuard.intercepts(
                studioId.value, SimulatedEffectChannel.EMAIL, user.email,
                "Link do ustawienia nowego hasła dla ${employee.firstName} ${employee.lastName}"
            )
        ) {
            EmailDeliveryResult.success("role-preview")
        } else {
            emailProvider.send(
                to = user.email,
                subject = SUBJECT,
                bodyText = body(employee.firstName, requestedByName, link, properties.tokenTtlMinutes)
            )
        }
        if (!result.success) {
            logger.warn(
                "Password reset e-mail failed [employeeId={}, userId={}]: {}",
                employeeId.value, user.id, result.errorMessage
            )
            throw UnprocessableEntityException(
                "Nie udało się wysłać maila na ${user.email}. Sprawdź adres i spróbuj ponownie."
            )
        }
        logger.info("Password reset link sent by a manager [employeeId={}, userId={}]", employeeId.value, user.id)

        auditService.log(LogAuditCommand(
            studioId = studioId,
            userId = requestedBy,
            userDisplayName = requestedByName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = employeeId.value.toString(),
            entityDisplayName = "${employee.firstName} ${employee.lastName}",
            action = AuditAction.STATUS_CHANGE,
            changes = listOf(FieldChange("password", null, "reset_link_sent"))
        ))

        PasswordResetSent(
            email = user.email,
            sentAt = now,
            expiresAt = now.plus(Duration.ofMinutes(properties.tokenTtlMinutes))
        )
    }

    private fun body(firstName: String, requestedByName: String?, link: String, ttlMinutes: Long): String {
        val who = requestedByName?.let { "Użytkownik $it poprosił" } ?: "Administrator poprosił"
        return """
            Cześć $firstName,

            $who o zmianę hasła do Twojego konta w DetailBoost.

            Aby ustawić nowe hasło, kliknij w poniższy link:
            $link

            Link jest aktywny przez $ttlMinutes minut. Dopóki nie ustawisz nowego hasła, działa dotychczasowe.

            Pozdrawiamy,
            Zespół DetailBoost
        """.trimIndent()
    }
}

data class PasswordResetSent(
    val email: String,
    val sentAt: Instant,
    val expiresAt: Instant
)
