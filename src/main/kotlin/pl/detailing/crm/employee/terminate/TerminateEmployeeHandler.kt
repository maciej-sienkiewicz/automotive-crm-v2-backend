package pl.detailing.crm.employee.terminate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.infrastructure.EmploymentContractRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Instant

@Service
class TerminateEmployeeHandler(
    private val employeeRepository: EmployeeRepository,
    private val contractRepository: EmploymentContractRepository,
    private val userRepository: UserRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: TerminateEmployeeCommand) = withContext(Dispatchers.IO) {
        val employeeEntity = employeeRepository.findByIdAndStudioId(command.employeeId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Pracownik '${command.employeeId}' nie został znaleziony")

        if (employeeEntity.status == EmployeeStatus.TERMINATED) {
            throw ValidationException("Pracownik jest już zwolniony")
        }

        // Mark employee terminated
        employeeEntity.status = EmployeeStatus.TERMINATED
        employeeEntity.terminationDate = command.terminationDate
        employeeEntity.updatedBy = command.userId.value
        employeeEntity.updatedAt = Instant.now()
        employeeRepository.save(employeeEntity)

        // Deactivate linked user account
        employeeEntity.userId?.let { linkedUserId ->
            val userEntity = userRepository.findByIdAndStudioId(linkedUserId, command.studioId.value)
            if (userEntity != null && userEntity.isActive) {
                userEntity.isActive = false
                userRepository.save(userEntity)
            }
        }

        // End any active contract
        val activeContract = contractRepository.findActiveByEmployeeIdAndStudioId(
            command.employeeId.value, command.studioId.value
        )
        if (activeContract != null) {
            activeContract.isActive = false
            activeContract.terminationDate = command.terminationDate
            activeContract.terminationReason = command.reason
            activeContract.updatedAt = Instant.now()
            contractRepository.save(activeContract)
        }

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = command.employeeId.value.toString(),
            entityDisplayName = "${employeeEntity.firstName} ${employeeEntity.lastName}",
            action = AuditAction.EMPLOYEE_TERMINATED,
            changes = listOf(
                FieldChange("status", "ACTIVE", "TERMINATED"),
                FieldChange("terminationDate", null, command.terminationDate.toString()),
                FieldChange("terminationReason", null, command.reason)
            )
        ))
    }
}
