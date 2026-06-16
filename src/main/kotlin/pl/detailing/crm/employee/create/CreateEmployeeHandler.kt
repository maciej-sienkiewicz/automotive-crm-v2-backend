package pl.detailing.crm.employee.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.account.ProvisionEmployeeAccountCommand
import pl.detailing.crm.employee.account.ProvisionEmployeeAccountHandler
import pl.detailing.crm.employee.domain.Employee
import pl.detailing.crm.employee.domain.EmployeeAddress
import pl.detailing.crm.employee.infrastructure.EmployeeEntity
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class CreateEmployeeHandler(
    private val validatorComposite: CreateEmployeeValidatorComposite,
    private val employeeRepository: EmployeeRepository,
    private val auditService: AuditService,
    private val provisionEmployeeAccountHandler: ProvisionEmployeeAccountHandler
) {
    @Transactional
    suspend fun handle(command: CreateEmployeeCommand): CreateEmployeeResult = withContext(Dispatchers.IO) {
        validatorComposite.validate(command)

        val address = if (command.addressStreet != null || command.addressCity != null || command.addressPostalCode != null) {
            EmployeeAddress(
                street = command.addressStreet ?: "",
                city = command.addressCity ?: "",
                postalCode = command.addressPostalCode ?: ""
            )
        } else null

        val employee = Employee(
            id = EmployeeId.random(),
            studioId = command.studioId,
            userId = command.linkedUserId,
            firstName = command.firstName.trim(),
            lastName = command.lastName.trim(),
            phone = command.phone?.trim(),
            email = command.email?.trim()?.lowercase(),
            personalEmail = command.personalEmail?.trim()?.lowercase(),
            pesel = command.pesel?.trim(),
            nip = command.nip?.trim(),
            address = address,
            position = command.position.trim(),
            hireDate = command.hireDate,
            terminationDate = null,
            status = EmployeeStatus.ACTIVE,
            notes = command.notes?.trim(),
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
                FieldChange("position", null, employee.position),
                FieldChange("hireDate", null, employee.hireDate.toString()),
                FieldChange("email", null, employee.email),
                FieldChange("linkedUserId", null, employee.userId?.toString())
            )
        ))

        if (command.createAccount) {
            val accountEmail = command.accountEmail?.trim()?.lowercase()
                ?: throw ValidationException("Adres e-mail konta jest wymagany przy tworzeniu konta użytkownika")
            val accountRole = command.accountRole
                ?: throw ValidationException("Rola konta jest wymagana przy tworzeniu konta użytkownika")
            if (accountRole == UserRole.OWNER) {
                throw ValidationException("Nie można nadać roli właściciela nowemu kontu pracownika")
            }
            provisionEmployeeAccountHandler.handle(
                ProvisionEmployeeAccountCommand(
                    studioId = command.studioId,
                    requestedBy = command.userId,
                    requestedByName = command.userName,
                    employeeId = employee.id,
                    email = accountEmail,
                    role = accountRole
                )
            )
        }

        CreateEmployeeResult(employeeId = employee.id, fullName = employee.fullName())
    }
}

data class CreateEmployeeResult(
    val employeeId: EmployeeId,
    val fullName: String
)
