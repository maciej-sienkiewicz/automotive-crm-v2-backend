package pl.detailing.crm.employee.compensation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.domain.CompensationConfig
import pl.detailing.crm.employee.infrastructure.CompensationConfigEntity
import pl.detailing.crm.employee.infrastructure.CompensationConfigRepository
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.shared.*
import java.math.RoundingMode
import java.time.Instant

@Service
class SetCompensationHandler(
    private val employeeRepository: EmployeeRepository,
    private val compensationConfigRepository: CompensationConfigRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: SetCompensationCommand): CompensationConfigId = withContext(Dispatchers.IO) {
        val employeeEntity = employeeRepository.findByIdAndStudioId(command.employeeId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Employee '${command.employeeId}' not found")

        if (employeeEntity.status == EmployeeStatus.TERMINATED) {
            throw ValidationException("Cannot set compensation for a terminated employee")
        }

        // Validate mode-specific requirements
        val (monthlySalaryGross, hourlyRateGross) = when (command.employmentMode) {
            EmploymentMode.SALARY -> {
                val fraction = command.etatFraction
                    ?: throw ValidationException("etatFraction is required when employmentMode = SALARY")
                val salary = command.monthlySalaryGross
                    ?: throw ValidationException("monthlySalaryGross is required when employmentMode = SALARY")
                // Derive hourly rate: monthlySalary / standardMonthlyHours
                val derivedRateCents = salary.amountInCents.toBigDecimal()
                    .divide(fraction.standardMonthlyHours, 0, RoundingMode.HALF_UP)
                    .toLong()
                Pair(salary, Money.fromCents(derivedRateCents))
            }
            EmploymentMode.HOURLY -> {
                val rate = command.hourlyRateGross
                    ?: throw ValidationException("hourlyRateGross is required when employmentMode = HOURLY")
                Pair(null, rate)
            }
        }

        // Close previous active config
        val current = compensationConfigRepository.findCurrentByEmployeeIdAndStudioId(
            command.employeeId.value, command.studioId.value
        )
        if (current != null) {
            current.effectiveTo = command.effectiveFrom.minusDays(1)
            current.updatedAt = Instant.now()
            compensationConfigRepository.save(current)
        }

        val config = CompensationConfig(
            id = CompensationConfigId.random(),
            studioId = command.studioId,
            employeeId = command.employeeId,
            contractId = command.contractId,
            effectiveFrom = command.effectiveFrom,
            effectiveTo = null,
            employmentMode = command.employmentMode,
            etatFraction = command.etatFraction,
            monthlySalaryGross = monthlySalaryGross,
            hourlyRateGross = hourlyRateGross,
            components = command.components,
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
            action = AuditAction.COMPENSATION_SET,
            changes = listOf(
                FieldChange("effectiveFrom", null, command.effectiveFrom.toString()),
                FieldChange("employmentMode", null, command.employmentMode.name),
                FieldChange("etatFraction", null, command.etatFraction?.name),
                FieldChange("monthlySalaryGross", null, monthlySalaryGross?.amountInCents?.toString()),
                FieldChange("hourlyRateGross", null, hourlyRateGross.amountInCents.toString()),
                FieldChange("componentsCount", null, command.components.size.toString())
            )
        ))

        config.id
    }
}
