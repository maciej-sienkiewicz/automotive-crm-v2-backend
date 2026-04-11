package pl.detailing.crm.employee.contract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.domain.CompensationConfig
import pl.detailing.crm.employee.domain.EmploymentContract
import pl.detailing.crm.employee.infrastructure.CompensationConfigEntity
import pl.detailing.crm.employee.infrastructure.CompensationConfigRepository
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.infrastructure.EmploymentContractEntity
import pl.detailing.crm.employee.infrastructure.EmploymentContractRepository
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class CreateContractHandler(
    private val employeeRepository: EmployeeRepository,
    private val contractRepository: EmploymentContractRepository,
    private val compensationConfigRepository: CompensationConfigRepository,
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
            terminationDate = null,
            terminationReason = null,
            isActive = true,
            documentFileId = command.documentFileId,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        contractRepository.save(EmploymentContractEntity.fromDomain(contract))

        // Close any current compensation config
        val currentConfig = compensationConfigRepository.findCurrentByEmployeeIdAndStudioId(
            command.employeeId.value, command.studioId.value
        )
        if (currentConfig != null) {
            currentConfig.effectiveTo = command.startDate.minusDays(1)
            currentConfig.updatedAt = Instant.now()
            compensationConfigRepository.save(currentConfig)
        }

        // Create initial compensation config from the embedded compensation data
        val comp = command.initialCompensation
        val (mode, etat, monthly, rateGross, rateNet) = when (comp) {
            is InitialCompensationData.Salary ->
                CompFields(EmploymentMode.SALARY, comp.etatFraction, Money.fromCents(comp.monthlySalaryGrossCents), null, null)
            is InitialCompensationData.HourlyGross ->
                CompFields(EmploymentMode.HOURLY, null, null, Money.fromCents(comp.hourlyRateGrossCents), null)
            is InitialCompensationData.HourlyNet ->
                CompFields(EmploymentMode.HOURLY, null, null, null, Money.fromCents(comp.hourlyRateNetCents))
        }

        val config = CompensationConfig(
            id = CompensationConfigId.random(),
            studioId = command.studioId,
            employeeId = command.employeeId,
            contractId = contract.id,
            effectiveFrom = command.startDate,
            effectiveTo = null,
            employmentMode = mode,
            etatFraction = etat,
            monthlySalaryGross = monthly,
            baseSalaryGross = null,
            hourlyRateGross = rateGross,
            hourlyRateNet = rateNet,
            components = emptyList(),
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        compensationConfigRepository.save(CompensationConfigEntity.fromDomain(config))

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
                FieldChange("employmentMode", null, mode.name)
            )
        ))

        contract.id
    }

    private data class CompFields(
        val mode: EmploymentMode,
        val etat: EtatFraction?,
        val monthly: Money?,
        val rateGross: Money?,
        val rateNet: Money?
    )
}
