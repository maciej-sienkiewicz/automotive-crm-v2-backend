package pl.detailing.crm.employee.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.user.infrastructure.UserRepository

@Service
class DeleteEmployeeAccountHandler(
    private val employeeRepository: EmployeeRepository,
    private val userRepository: UserRepository,
    private val auditService: AuditService,
    private val transactionTemplate: TransactionTemplate
) {
    @Transactional
    suspend fun handle(
        studioId: StudioId,
        employeeId: EmployeeId,
        requestedBy: UserId,
        requestedByName: String?
    ) = withContext(Dispatchers.IO) {
        val employeeEntity = employeeRepository.findByIdAndStudioId(employeeId.value, studioId.value)
            ?: throw EntityNotFoundException("Pracownik nie istnieje")

        val userId = employeeEntity.userId
            ?: throw ValidationException("Pracownik nie ma powiązanego konta użytkownika")

        val userEntity = userRepository.findByIdAndStudioId(userId, studioId.value)
            ?: throw EntityNotFoundException("Konto użytkownika nie istnieje")

        val deletedEmail = userEntity.email

        // One real transaction (TransactionTemplate — the body of a `@Transactional
        // suspend` function running on Dispatchers.IO escapes the interceptor-managed
        // transaction; see AuditLogWriter). Without it the employee could end up
        // unlinked from an account that still exists and can still sign in.
        transactionTemplate.execute {
            employeeEntity.userId = null
            employeeRepository.save(employeeEntity)

            userRepository.delete(userEntity)
        }

        auditService.log(LogAuditCommand(
            studioId = studioId,
            userId = requestedBy,
            userDisplayName = requestedByName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = employeeId.value.toString(),
            entityDisplayName = "${employeeEntity.firstName} ${employeeEntity.lastName}",
            action = AuditAction.DELETE,
            changes = listOf(FieldChange("account", deletedEmail, null))
        ))
    }
}
