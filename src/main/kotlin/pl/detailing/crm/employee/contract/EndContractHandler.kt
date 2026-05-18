package pl.detailing.crm.employee.contract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.infrastructure.EmploymentContractRepository
import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.LocalDate

data class EndContractCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val employeeId: EmployeeId,
    val contractId: EmploymentContractId,
    val terminationDate: LocalDate,
    val terminationReason: String?
)

@Service
class EndContractHandler(
    private val employeeRepository: EmployeeRepository,
    private val contractRepository: EmploymentContractRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: EndContractCommand) = withContext(Dispatchers.IO) {
        val employeeEntity = employeeRepository.findByIdAndStudioId(command.employeeId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Pracownik '${command.employeeId}' nie został znaleziony")

        val contractEntity = contractRepository.findByIdAndStudioId(command.contractId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Umowa '${command.contractId}' nie została znaleziona")

        if (!contractEntity.isActive) {
            throw ValidationException("Umowa jest już nieaktywna")
        }

        contractEntity.isActive = false
        contractEntity.terminationDate = command.terminationDate
        contractEntity.terminationReason = command.terminationReason
        contractEntity.updatedAt = Instant.now()
        contractRepository.save(contractEntity)

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = command.employeeId.value.toString(),
            entityDisplayName = "${employeeEntity.firstName} ${employeeEntity.lastName}",
            action = AuditAction.CONTRACT_ENDED,
            changes = listOf(
                FieldChange("contractId", null, command.contractId.toString()),
                FieldChange("terminationDate", null, command.terminationDate.toString()),
                FieldChange("terminationReason", null, command.terminationReason)
            )
        ))
    }
}
