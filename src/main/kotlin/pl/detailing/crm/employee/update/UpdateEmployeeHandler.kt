package pl.detailing.crm.employee.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class UpdateEmployeeHandler(
    private val employeeRepository: EmployeeRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: UpdateEmployeeCommand) = withContext(Dispatchers.IO) {
        val entity = employeeRepository.findByIdAndStudioId(command.employeeId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Pracownik '${command.employeeId}' nie został znaleziony")

        if (entity.status == EmployeeStatus.TERMINATED) {
            throw ValidationException("Nie można aktualizować danych zwolnionego pracownika")
        }

        val oldValues = mapOf(
            "firstName" to entity.firstName,
            "lastName" to entity.lastName,
            "position" to entity.position,
            "email" to entity.email,
            "phone" to entity.phone,
            "linkedUserId" to entity.userId?.toString()
        )

        entity.userId = command.linkedUserId?.value
        entity.firstName = command.firstName.trim()
        entity.lastName = command.lastName.trim()
        entity.phone = command.phone?.trim()
        entity.email = command.email?.trim()?.lowercase()
        entity.personalEmail = command.personalEmail?.trim()?.lowercase()
        entity.pesel = command.pesel?.trim()
        entity.nip = command.nip?.trim()
        entity.addressStreet = command.addressStreet?.trim()
        entity.addressCity = command.addressCity?.trim()
        entity.addressPostalCode = command.addressPostalCode?.trim()
        entity.position = command.position.trim()
        entity.hireDate = command.hireDate
        entity.notes = command.notes?.trim()
        entity.updatedBy = command.userId.value
        entity.updatedAt = Instant.now()

        employeeRepository.save(entity)

        val newValues = mapOf(
            "firstName" to entity.firstName,
            "lastName" to entity.lastName,
            "position" to entity.position,
            "email" to entity.email,
            "phone" to entity.phone,
            "linkedUserId" to entity.userId?.toString()
        )

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = command.employeeId.value.toString(),
            entityDisplayName = "${command.firstName.trim()} ${command.lastName.trim()}",
            action = AuditAction.UPDATE,
            changes = auditService.computeChanges(oldValues, newValues)
        ))
    }
}
