package pl.detailing.crm.employee.contract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.domain.EmploymentContract
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.infrastructure.EmploymentContractEntity
import pl.detailing.crm.employee.infrastructure.EmploymentContractRepository
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class CreateContractHandler(
    private val employeeRepository: EmployeeRepository,
    private val contractRepository: EmploymentContractRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: CreateContractCommand): EmploymentContractId = withContext(Dispatchers.IO) {
        val employeeEntity = employeeRepository.findByIdAndStudioId(command.employeeId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Employee '${command.employeeId}' not found")

        if (employeeEntity.status == EmployeeStatus.TERMINATED) {
            throw ValidationException("Cannot add a contract to a terminated employee")
        }

        // Deactivate any existing active contract
        val existingActive = contractRepository.findActiveByEmployeeIdAndStudioId(
            command.employeeId.value, command.studioId.value
        )
        if (existingActive != null) {
            existingActive.isActive = false
            existingActive.updatedAt = Instant.now()
            contractRepository.save(existingActive)
        }

        val contract = EmploymentContract(
            id = EmploymentContractId.random(),
            studioId = command.studioId,
            employeeId = command.employeeId,
            contractType = command.contractType,
            startDate = command.startDate,
            endDate = command.endDate,
            workingHoursPerWeek = command.workingHoursPerWeek,
            trialPeriodEndDate = command.trialPeriodEndDate,
            terminationDate = null,
            terminationReason = null,
            isActive = true,
            documentFileId = command.documentFileId,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        contractRepository.save(EmploymentContractEntity.fromDomain(contract))

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = command.employeeId.value.toString(),
            entityDisplayName = "${employeeEntity.firstName} ${employeeEntity.lastName}",
            action = AuditAction.CONTRACT_CREATED,
            changes = listOf(
                FieldChange("contractType", null, command.contractType.name),
                FieldChange("startDate", null, command.startDate.toString()),
                FieldChange("workingHoursPerWeek", null, command.workingHoursPerWeek.toPlainString())
            )
        ))

        contract.id
    }
}
