package pl.detailing.crm.employee.delete

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.user.infrastructure.UserRepository

@Service
class DeleteEmployeeHandler(
    private val employeeRepository: EmployeeRepository,
    private val employeeLeaveRepository: EmployeeLeaveRepository,
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

        // One real transaction (TransactionTemplate — the body of a `@Transactional
        // suspend` function running on Dispatchers.IO escapes the interceptor-managed
        // transaction; see AuditLogWriter). Without it the login account could be
        // destroyed while the employee row survived — someone who can no longer sign in
        // but still occupies the roster and the team calendar.
        val fullName = "${employeeEntity.firstName} ${employeeEntity.lastName}"

        transactionTemplate.execute {
            // Remove linked user account if present
            employeeEntity.userId?.let { userId ->
                userRepository.findByIdAndStudioId(userId, studioId.value)?.let { userRepository.delete(it) }
            }

            // Urlopy pracownika nie mogą pozostać osierocone — zasilają kalendarz zespołu
            employeeLeaveRepository.deleteByStudioIdAndEmployeeId(studioId.value, employeeId.value)
            employeeRepository.delete(employeeEntity)
        }

        auditService.log(LogAuditCommand(
            studioId = studioId,
            userId = requestedBy,
            userDisplayName = requestedByName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = employeeId.value.toString(),
            entityDisplayName = fullName,
            action = AuditAction.DELETE,
            changes = emptyList()
        ))
    }
}
