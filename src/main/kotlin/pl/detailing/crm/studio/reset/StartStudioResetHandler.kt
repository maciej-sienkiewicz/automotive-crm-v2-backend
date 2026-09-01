package pl.detailing.crm.studio.reset

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.UnauthorizedException
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.user.infrastructure.UserRepository

data class StartStudioResetCommand(
    val principal: UserPrincipal,
    val currentPassword: String,
    val confirmationName: String,
    val wipeCompanyData: Boolean
)

/**
 * Zleca wyczyszczenie konta: weryfikuje hasło ownera (wzorem
 * [pl.detailing.crm.pin.SetPinHandler]) i przepisaną nazwę firmy, po czym zapisuje
 * job PENDING, który podejmie [StudioResetJobRunner]. Samo kasowanie danych nie dzieje
 * się w żądaniu HTTP — pełny purge ~140 tabel i S3 trwa zbyt długo na request/response
 * i musi być wznawialny po restarcie instancji.
 */
@Service
class StartStudioResetHandler(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val studioRepository: StudioRepository,
    private val studioSettingsRepository: StudioSettingsRepository,
    private val studioResetJobRepository: StudioResetJobRepository,
    private val auditService: AuditService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun handle(command: StartStudioResetCommand): StudioResetJobEntity = withContext(Dispatchers.IO) {
        val principal = command.principal
        val studioId = principal.studioId.value
        val userId = principal.userId.value

        val user = userRepository.findByIdAndStudioId(userId, studioId)
            ?: throw UnauthorizedException("Nie znaleziono użytkownika")

        if (!passwordEncoder.matches(command.currentPassword, user.passwordHash)) {
            throw UnauthorizedException("Nieprawidłowe hasło")
        }

        val expectedName = studioSettingsRepository.findById(studioId).orElse(null)?.name
            ?.takeIf { it.isNotBlank() }
            ?: studioRepository.findByStudioId(studioId)?.name
            ?: throw ValidationException("Nie udało się ustalić nazwy firmy")

        if (command.confirmationName.trim() != expectedName.trim()) {
            throw ValidationException("Wpisana nazwa nie zgadza się z nazwą firmy")
        }

        // Zwykła walidacja zamiast unikalnego indeksu: teoretyczny wyścig dwóch żądań
        // jest niegroźny, bo kroki resetu są idempotentne, a runner wykonuje joby
        // sekwencyjnie — drugi przebieg na pustym koncie niczego nie psuje.
        studioResetJobRepository.findActiveByStudioId(studioId)?.let {
            throw ConflictException("Czyszczenie konta jest już w toku")
        }

        val job = studioResetJobRepository.save(
            StudioResetJobEntity(
                studioId = studioId,
                requestedBy = userId,
                requestedByName = principal.fullName,
                wipeCompanyData = command.wipeCompanyData
            )
        )

        // Wpis CRITICAL przed purge — audit_logs przeżywają reset, więc ślad tej decyzji
        // zostaje na zawsze. logSync nigdy nie rzuca (kontrakt AuditLogWriter).
        auditService.logSync(
            LogAuditCommand(
                studioId = principal.studioId,
                userId = principal.userId,
                userDisplayName = principal.fullName,
                module = AuditModule.STUDIO,
                entityId = studioId.toString(),
                entityDisplayName = expectedName,
                action = AuditAction.ACCOUNT_RESET_STARTED,
                metadata = mapOf(
                    "jobId" to job.id.toString(),
                    "wipeCompanyData" to command.wipeCompanyData.toString()
                )
            )
        )

        logger.warn(
            "Account reset requested: studioId={}, jobId={}, requestedBy={}, wipeCompanyData={}",
            studioId, job.id, userId, command.wipeCompanyData
        )

        job
    }
}
