package pl.detailing.crm.employee.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.user.infrastructure.UserRepository

@Service
class BlockEmployeeAccountHandler(
    private val employeeRepository: EmployeeRepository,
    private val userRepository: UserRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(
        studioId: StudioId,
        employeeId: EmployeeId,
        block: Boolean,
        requestedBy: UserId,
        requestedByName: String?
    ) = withContext(Dispatchers.IO) {
        val employeeEntity = employeeRepository.findByIdAndStudioId(employeeId.value, studioId.value)
            ?: throw EntityNotFoundException("Pracownik nie istnieje")

        val userId = employeeEntity.userId
            ?: throw ValidationException("Pracownik nie ma powiązanego konta użytkownika")

        val userEntity = userRepository.findByIdAndStudioId(userId, studioId.value)
            ?: throw EntityNotFoundException("Konto użytkownika nie istnieje")

        val previousState = userEntity.isActive
        userEntity.isActive = !block
        userRepository.save(userEntity)

        val action = if (block) "zablokowane" else "odblokowane"
        auditService.log(LogAuditCommand(
            studioId = studioId,
            userId = requestedBy,
            userDisplayName = requestedByName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = employeeId.value.toString(),
            entityDisplayName = "${employeeEntity.firstName} ${employeeEntity.lastName}",
            action = AuditAction.STATUS_CHANGE,
            changes = listOf(FieldChange("accountIsActive", previousState.toString(), (!block).toString()))
        ))
    }
}
