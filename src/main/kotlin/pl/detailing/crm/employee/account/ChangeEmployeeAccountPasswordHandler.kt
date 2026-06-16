package pl.detailing.crm.employee.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.auth.PasswordPolicy
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.user.infrastructure.UserRepository

@Service
class ChangeEmployeeAccountPasswordHandler(
    private val employeeRepository: EmployeeRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val passwordPolicy: PasswordPolicy,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(
        studioId: StudioId,
        employeeId: EmployeeId,
        newPassword: String,
        confirmPassword: String,
        requestedBy: UserId,
        requestedByName: String?
    ) = withContext(Dispatchers.IO) {
        passwordPolicy.validate(newPassword, confirmPassword)

        val employeeEntity = employeeRepository.findByIdAndStudioId(employeeId.value, studioId.value)
            ?: throw EntityNotFoundException("Pracownik nie istnieje")

        val userId = employeeEntity.userId
            ?: throw ValidationException("Pracownik nie ma powiązanego konta użytkownika")

        val userEntity = userRepository.findByIdAndStudioId(userId, studioId.value)
            ?: throw EntityNotFoundException("Konto użytkownika nie istnieje")

        userEntity.passwordHash = passwordEncoder.encode(newPassword)
        userRepository.save(userEntity)

        auditService.log(LogAuditCommand(
            studioId = studioId,
            userId = requestedBy,
            userDisplayName = requestedByName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = employeeId.value.toString(),
            entityDisplayName = "${employeeEntity.firstName} ${employeeEntity.lastName}",
            action = AuditAction.UPDATE,
            changes = listOf(FieldChange("passwordHash", null, "[zmienione]"))
        ))
    }
}
