package pl.detailing.crm.employee.delete

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
    private val auditService: AuditService
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

        // Remove linked user account if present
        employeeEntity.userId?.let { userId ->
            userRepository.findByIdAndStudioId(userId, studioId.value)?.let { userRepository.delete(it) }
        }

        val fullName = "${employeeEntity.firstName} ${employeeEntity.lastName}"

        // Urlopy pracownika nie mogą pozostać osierocone — zasilają kalendarz zespołu
        employeeLeaveRepository.deleteByStudioIdAndEmployeeId(studioId.value, employeeId.value)
        employeeRepository.delete(employeeEntity)

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
