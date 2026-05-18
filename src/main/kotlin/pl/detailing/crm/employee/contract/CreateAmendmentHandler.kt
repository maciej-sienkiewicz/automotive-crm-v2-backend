package pl.detailing.crm.employee.contract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.domain.CompensationConfig
import pl.detailing.crm.employee.domain.ContractAmendment
import pl.detailing.crm.employee.infrastructure.*
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class CreateAmendmentHandler(
    private val employeeRepository: EmployeeRepository,
    private val contractRepository: EmploymentContractRepository,
    private val amendmentRepository: ContractAmendmentRepository,
    private val compensationConfigRepository: CompensationConfigRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: CreateAmendmentCommand): ContractAmendmentId = withContext(Dispatchers.IO) {
        val employeeEntity = employeeRepository.findByIdAndStudioId(command.employeeId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Pracownik '${command.employeeId}' nie został znaleziony")

        contractRepository.findByIdAndStudioId(command.contractId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Umowa '${command.contractId}' nie została znaleziona")

        val comp = command.compensation
        val (mode, etat, monthly, rateGross, rateNet) = when (comp) {
            is InitialCompensationData.Salary ->
                CompFields(EmploymentMode.SALARY, comp.etatFraction, Money.fromCents(comp.monthlySalaryGrossCents), null, null)
            is InitialCompensationData.HourlyGross ->
                CompFields(EmploymentMode.HOURLY, null, null, Money.fromCents(comp.hourlyRateGrossCents), null)
            is InitialCompensationData.HourlyNet ->
                CompFields(EmploymentMode.HOURLY, null, null, null, Money.fromCents(comp.hourlyRateNetCents))
        }

        // Close previous amendment
        val previousAmendment = amendmentRepository.findFirstByContractIdOrderByEffectiveFromDesc(command.contractId.value)
        if (previousAmendment != null && previousAmendment.effectiveTo == null) {
            previousAmendment.effectiveTo = command.effectiveFrom.minusDays(1)
            amendmentRepository.save(previousAmendment)
        }

        val amendment = ContractAmendment(
            id = ContractAmendmentId.random(),
            studioId = command.studioId,
            employeeId = command.employeeId,
            contractId = command.contractId,
            effectiveFrom = command.effectiveFrom,
            effectiveTo = null,
            employmentMode = mode,
            etatFraction = etat,
            monthlySalaryGross = monthly,
            hourlyRateGross = rateGross,
            hourlyRateNet = rateNet,
            createdAt = Instant.now()
        )
        amendmentRepository.save(ContractAmendmentEntity.fromDomain(amendment))

        // Also update compensation config – close the current one and open a new one
        val currentConfig = compensationConfigRepository.findCurrentByEmployeeIdAndStudioId(
            command.employeeId.value, command.studioId.value
        )
        val existingComponents = currentConfig?.components?.map { it.toDomain() } ?: emptyList()
        val existingBaseSalary = currentConfig?.baseSalaryGross?.let { Money.fromCents(it) }

        if (currentConfig != null) {
            currentConfig.effectiveTo = command.effectiveFrom.minusDays(1)
            currentConfig.updatedAt = Instant.now()
            compensationConfigRepository.save(currentConfig)
        }

        val newConfig = CompensationConfig(
            id = CompensationConfigId.random(),
            studioId = command.studioId,
            employeeId = command.employeeId,
            contractId = command.contractId,
            effectiveFrom = command.effectiveFrom,
            effectiveTo = null,
            employmentMode = mode,
            etatFraction = etat,
            monthlySalaryGross = monthly,
            baseSalaryGross = existingBaseSalary,
            hourlyRateGross = rateGross,
            hourlyRateNet = rateNet,
            components = existingComponents,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        compensationConfigRepository.save(CompensationConfigEntity.fromDomain(newConfig))

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = command.employeeId.value.toString(),
            entityDisplayName = "${employeeEntity.firstName} ${employeeEntity.lastName}",
            action = AuditAction.COMPENSATION_SET,
            changes = listOf(
                FieldChange("amendmentEffectiveFrom", null, command.effectiveFrom.toString()),
                FieldChange("employmentMode", null, mode.name)
            )
        ))

        amendment.id
    }

    private data class CompFields(
        val mode: EmploymentMode,
        val etat: EtatFraction?,
        val monthly: Money?,
        val rateGross: Money?,
        val rateNet: Money?
    )
}
