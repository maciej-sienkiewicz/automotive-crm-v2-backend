package pl.detailing.crm.employee.payroll

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.domain.PayrollComponentBreakdown
import pl.detailing.crm.employee.domain.PayrollEntry
import pl.detailing.crm.employee.infrastructure.*
import pl.detailing.crm.shared.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

@Service
class GeneratePayrollHandler(
    private val employeeRepository: EmployeeRepository,
    private val contractRepository: EmploymentContractRepository,
    private val compensationConfigRepository: CompensationConfigRepository,
    private val workTimeRepository: WorkTimeEntryRepository,
    private val payrollRepository: PayrollEntryRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: GeneratePayrollCommand): PayrollEntryId = withContext(Dispatchers.IO) {
        val employeeEntity = employeeRepository.findByIdAndStudioId(command.employeeId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Employee '${command.employeeId}' not found")

        if (payrollRepository.existsByEmployeeIdAndPeriod(
                command.employeeId.value, command.studioId.value, command.period.toString()
            )
        ) {
            throw ConflictException("Payroll for ${command.period} already exists for this employee")
        }

        val contract = contractRepository.findActiveByEmployeeIdAndStudioId(
            command.employeeId.value, command.studioId.value
        ) ?: throw EntityNotFoundException("No active contract found for employee '${command.employeeId}'")

        val compensationConfig = compensationConfigRepository.findCurrentByEmployeeIdAndStudioId(
            command.employeeId.value, command.studioId.value
        ) ?: throw EntityNotFoundException("No active compensation config found for employee '${command.employeeId}'")

        // Get approved work time entries for the period
        val from = command.period.atDay(1)
        val to = command.period.atEndOfMonth()
        val workTimeEntries = workTimeRepository.findByEmployeeIdAndDateRange(
            command.employeeId.value, command.studioId.value, from, to
        ).filter { it.status == WorkTimeStatus.APPROVED }.map { it.toDomain() }

        val totalHoursWorked = workTimeEntries.fold(BigDecimal.ZERO) { acc, e -> acc + e.effectiveHours }

        // Calculate component breakdown
        val breakdowns = mutableListOf<PayrollComponentBreakdown>()

        val baseSalaryGross: Money
        when (compensationConfig.employmentMode) {
            EmploymentMode.SALARY -> {
                // Fixed monthly salary – does not depend on hours logged
                baseSalaryGross = compensationConfig.monthlySalaryGross ?: Money.ZERO

                // Overtime: hours beyond the standard monthly norm are paid at hourly rate × 1.5
                val standardHours = compensationConfig.etatFraction?.standardMonthlyHours
                    ?: EtatFraction.FULL.standardMonthlyHours
                val hourlyRate = compensationConfig.hourlyRateGross
                if (hourlyRate != null && totalHoursWorked > standardHours) {
                    val overtimeHours = totalHoursWorked - standardHours
                    val overtimeAmount = Money.fromCents(
                        (hourlyRate.amountInCents.toBigDecimal() * overtimeHours * BigDecimal("1.5"))
                            .setScale(0, RoundingMode.HALF_UP).toLong()
                    )
                    breakdowns.add(
                        PayrollComponentBreakdown(
                            componentName = "Nadgodziny",
                            calculatedAmount = overtimeAmount,
                            calculationDetails = "${hourlyRate.amountInCents / 100.0} PLN/h × ${overtimeHours.toPlainString()} h × 1.5 = ${overtimeAmount.amountInCents / 100.0} PLN"
                        )
                    )
                }
            }
            EmploymentMode.HOURLY -> {
                // Godzinówka – pay is entirely based on approved hours
                baseSalaryGross = Money.ZERO
                val hourlyRate = compensationConfig.hourlyRateGross
                if (hourlyRate != null && totalHoursWorked > BigDecimal.ZERO) {
                    val hourlyAmount = Money.fromCents(
                        (hourlyRate.amountInCents.toBigDecimal() * totalHoursWorked)
                            .setScale(0, RoundingMode.HALF_UP).toLong()
                    )
                    breakdowns.add(
                        PayrollComponentBreakdown(
                            componentName = "Wynagrodzenie godzinowe",
                            calculatedAmount = hourlyAmount,
                            calculationDetails = "${hourlyRate.amountInCents / 100.0} PLN/h × ${totalHoursWorked.toPlainString()} h = ${hourlyAmount.amountInCents / 100.0} PLN"
                        )
                    )
                }
            }
        }

        // Custom compensation components
        for (component in compensationConfig.components.filter { it.isActive }) {
            val calculated = when (component.type) {
                ComponentType.FIXED_AMOUNT -> Money.fromCents(component.value.toLong() * 100)
                ComponentType.PERCENTAGE_OF_BASE -> {
                    val base = baseSalaryGross.amountInCents.toBigDecimal()
                    val amount = (base * component.value / BigDecimal("100"))
                        .setScale(0, RoundingMode.HALF_UP).toLong()
                    Money.fromCents(amount)
                }
                ComponentType.PER_HOUR_BONUS -> {
                    val amount = (totalHoursWorked * component.value * BigDecimal("100"))
                        .setScale(0, RoundingMode.HALF_UP).toLong()
                    Money.fromCents(amount)
                }
                else -> Money.ZERO
            }

            if (calculated.amountInCents > 0) {
                breakdowns.add(
                    PayrollComponentBreakdown(
                        componentName = component.name,
                        calculatedAmount = calculated,
                        calculationDetails = "Typ: ${component.type.name}, wartość: ${component.value.toPlainString()}"
                    )
                )
            }
        }

        val componentTotal = breakdowns.fold(Money.ZERO) { acc, b -> acc.plus(b.calculatedAmount) }
        val totalGross = baseSalaryGross.plus(componentTotal)

        val payrollEntry = PayrollEntry(
            id = PayrollEntryId.random(),
            studioId = command.studioId,
            employeeId = command.employeeId,
            contractId = EmploymentContractId(contract.id),
            period = command.period,
            baseSalaryGross = baseSalaryGross,
            totalHoursWorked = totalHoursWorked,
            componentBreakdown = breakdowns,
            totalGross = totalGross,
            totalNet = null,
            employerCostTotal = null,
            status = PayrollStatus.DRAFT,
            notes = command.notes,
            confirmedBy = null,
            confirmedAt = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        payrollRepository.save(PayrollEntryEntity.fromDomain(payrollEntry))

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = command.employeeId.value.toString(),
            entityDisplayName = "${employeeEntity.firstName} ${employeeEntity.lastName}",
            action = AuditAction.PAYROLL_GENERATED,
            changes = listOf(
                FieldChange("period", null, command.period.toString()),
                FieldChange("totalGross", null, totalGross.amountInCents.toString()),
                FieldChange("totalHours", null, totalHoursWorked.toPlainString())
            )
        ))

        payrollEntry.id
    }
}
