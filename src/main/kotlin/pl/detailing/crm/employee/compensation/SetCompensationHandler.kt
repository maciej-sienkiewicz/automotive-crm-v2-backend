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
            ?: throw EntityNotFoundException("Pracownik '${command.employeeId}' nie został znaleziony")

        if (employeeEntity.status == EmployeeStatus.TERMINATED) {
            throw ValidationException("Nie można ustawić wynagrodzenia dla zwolnionego pracownika")
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
            monthlySalaryGross = command.monthlySalaryGross,
            baseSalaryGross = command.baseSalaryGross,
            hourlyRateGross = command.hourlyRateGross,
            hourlyRateNet = command.hourlyRateNet,
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
                FieldChange("monthlySalaryGross", null, command.monthlySalaryGross?.amountInCents?.toString()),
                FieldChange("hourlyRateGross", null, command.hourlyRateGross?.amountInCents?.toString()),
                FieldChange("hourlyRateNet", null, command.hourlyRateNet?.amountInCents?.toString()),
                FieldChange("componentsCount", null, command.components.size.toString())
            )
        ))

        config.id
    }
}
