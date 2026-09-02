package pl.detailing.crm.employee.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.auth.passwordreset.PasswordResetProperties
import pl.detailing.crm.auth.passwordreset.PasswordResetTokenService
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Instant
import java.util.UUID

@Service
class ProvisionEmployeeAccountHandler(
    private val employeeRepository: EmployeeRepository,
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokenService: PasswordResetTokenService,
    private val emailProvider: EmailProvider,
    private val auditService: AuditService,
    private val properties: PasswordResetProperties,
    private val transactionTemplate: TransactionTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(command: ProvisionEmployeeAccountCommand): UserId = withContext(Dispatchers.IO) {
        val employeeEntity = employeeRepository.findByIdAndStudioId(command.employeeId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Pracownik nie istnieje")

        if (employeeEntity.userId != null) {
            throw ConflictException("Pracownik posiada już powiązane konto użytkownika")
        }

        val email = command.email.trim().lowercase()

        if (userRepository.existsByEmailAndStudioId(email, command.studioId.value)) {
            throw ValidationException("Adres e-mail '$email' jest już zajęty w tej firmie")
        }

        val userId = UUID.randomUUID()
        // The role id comes from the client — it has to be one of THIS studio's roles.
        command.roleId?.let { roleId ->
            roleRepository.findByIdAndStudioId(roleId.value, command.studioId.value)
                ?: throw EntityNotFoundException("Rola nie istnieje")
        }

        val userEntity = UserEntity(
            id = userId,
            studioId = command.studioId.value,
            email = email,
            phoneNumber = employeeEntity.phone ?: "",
            passwordHash = passwordEncoder.encode(UUID.randomUUID().toString()),
            firstName = employeeEntity.firstName,
            lastName = employeeEntity.lastName,
            isOwner = false,
            isActive = true,
            createdAt = Instant.now(),
            customRoleId = command.roleId?.value
        )
        // One real transaction (TransactionTemplate — the body of a `@Transactional
        // suspend` function running on Dispatchers.IO escapes the interceptor-managed
        // transaction; see AuditLogWriter). Without it a failure after the users row
        // was written left that row holding the employee's e-mail, and the uniqueness
        // guard above then rejected every retry — the employee could never be invited.
        val rawToken = transactionTemplate.execute {
            userRepository.save(userEntity)

            employeeEntity.userId = userId
            employeeRepository.save(employeeEntity)

            tokenService.issueToken(userId)
        } ?: error("Transakcja tworzenia konta pracownika nie zwróciła wyniku")

        // Delivery is best-effort and deliberately outside the transaction: a bounced
        // invitation must not undo an account that exists, and the link can be re-sent.
        val setupLink = "${properties.frontendBaseUrl.trimEnd('/')}/confirm-password?token=$rawToken"

        val emailResult = emailProvider.send(
            to = email,
            subject = "Zaproszenie do DetailBoost – skonfiguruj swoje konto",
            bodyText = buildInvitationBody(
                firstName = employeeEntity.firstName,
                invitedByName = command.requestedByName,
                setupLink = setupLink,
                tokenTtlMinutes = properties.tokenTtlMinutes
            )
        )

        if (emailResult.success) {
            logger.info("Invitation email sent [employeeId={}, userId={}]", command.employeeId.value, userId)
        } else {
            logger.warn(
                "Invitation email delivery failed [employeeId={}, userId={}]: {}",
                command.employeeId.value, userId, emailResult.errorMessage
            )
        }

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.requestedBy,
            userDisplayName = command.requestedByName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = command.employeeId.value.toString(),
            entityDisplayName = "${employeeEntity.firstName} ${employeeEntity.lastName}",
            action = AuditAction.STATUS_CHANGE,
            changes = listOf(
                FieldChange("account", null, "created"),
                FieldChange("accountEmail", null, email),
                FieldChange("accountRole", null, command.roleId?.toString() ?: "USER")
            )
        ))

        UserId(userId)
    }

    private fun buildInvitationBody(
        firstName: String,
        invitedByName: String?,
        setupLink: String,
        tokenTtlMinutes: Long
    ): String {
        val inviter = invitedByName?.let { "Użytkownik $it" } ?: "Administrator"
        return """
            Cześć $firstName,

            $inviter zaprosił(-a) Cię do platformy DetailBoost.

            Aby aktywować swoje konto i ustawić hasło, kliknij w poniższy link:
            $setupLink

            Link jest aktywny przez $tokenTtlMinutes minut. Po tym czasie wygaśnie i będziesz musiał(-a) poprosić administratora o ponowne wysłanie zaproszenia.

            Jeśli nie spodziewałeś(-aś) się tego zaproszenia, możesz zignorować tę wiadomość.

            Pozdrawiamy,
            Zespół DetailBoost
        """.trimIndent()
    }
}
