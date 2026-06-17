package pl.detailing.crm.employee.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.domain.Employee
import pl.detailing.crm.employee.infrastructure.EmployeeEntity
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class CreateEmployeeHandler(
    private val validatorComposite: CreateEmployeeValidatorComposite,
    private val employeeRepository: EmployeeRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: CreateEmployeeCommand): CreateEmployeeResult = withContext(Dispatchers.IO) {
        validatorComposite.validate(command)

        val employee = Employee(
            id = EmployeeId.random(),
            studioId = command.studioId,
            userId = null,
            firstName = command.firstName.trim(),
            lastName = command.lastName.trim(),
            phone = command.phone?.trim(),
            email = command.email?.trim()?.lowercase(),
            createdBy = command.userId,
            updatedBy = command.userId,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        val entity = EmployeeEntity.fromDomain(employee)
        employeeRepository.save(entity)

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = employee.id.value.toString(),
            entityDisplayName = employee.fullName(),
            action = AuditAction.CREATE,
            changes = listOf(
                FieldChange("firstName", null, employee.firstName),
                FieldChange("lastName", null, employee.lastName),
                FieldChange("email", null, employee.email)
            )
        ))

        CreateEmployeeResult(employeeId = employee.id, fullName = employee.fullName())
    }
}

data class CreateEmployeeResult(
    val employeeId: EmployeeId,
    val fullName: String
)
