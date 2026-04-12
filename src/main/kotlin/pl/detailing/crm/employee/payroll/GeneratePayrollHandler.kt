package pl.detailing.crm.employee.payroll

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.domain.BonusEntryStatus
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
    private val bonusRepository: BonusEntryRepository,
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

        val compensationConfig = compensationConfigRepository.findForDate(
            command.employeeId.value, command.studioId.value, command.period.atDay(1)
        ) ?: throw EntityNotFoundException("No active compensation config found for employee '${command.employeeId}' in period ${command.period}")

        // Approved work time entries for the period
        val from = command.period.atDay(1)
        val to = command.period.atEndOfMonth()
        val workTimeEntries = workTimeRepository.findByEmployeeIdAndDateRange(
            command.employeeId.value, command.studioId.value, from, to
        ).filter { it.status == WorkTimeStatus.APPROVED }.map { it.toDomain() }

        val totalHoursWorked = workTimeEntries.fold(BigDecimal.ZERO) { acc, e -> acc + e.effectiveHours }

        // Pending bonus entries for the period
        val pendingBonuses = bonusRepository.findByEmployeeIdAndStudioIdAndPeriodOrderByCreatedAtAsc(
            command.employeeId.value, command.studioId.value, command.period.toString()
        ).filter { it.status == BonusEntryStatus.PENDING }

        // Resolve base pay
        val config = compensationConfig.toDomain()
        val baseSalaryGross = config.baseSalaryGross ?: config.monthlySalaryGross ?: Money.ZERO
        val hourlyRateGross = config.hourlyRateGross
        val hourlyRateNet = config.hourlyRateNet

        // Revenue inputs (for PERCENTAGE_OF_REVENUE components)
        val revenueGross = command.revenueGrossCents?.let { Money.fromCents(it) }
        val revenueNet = command.revenueNetCents?.let { Money.fromCents(it) }

        val breakdowns = mutableListOf<PayrollComponentBreakdown>()

        // ── Base pay ──────────────────────────────────────────────────────────

        // Hourly base pay for UZ / B2B
        val effectiveHourlyRate = hourlyRateGross ?: hourlyRateNet
        if (effectiveHourlyRate != null && totalHoursWorked > BigDecimal.ZERO) {
            val hourlyAmount = Money.fromCents(
                (effectiveHourlyRate.amountInCents.toBigDecimal() * totalHoursWorked)
                    .setScale(0, RoundingMode.HALF_UP).toLong()
            )
            val rateLabel = if (hourlyRateNet != null) "netto" else "brutto"
            breakdowns.add(
                PayrollComponentBreakdown(
                    componentName = "Stawka godzinowa",
                    calculatedAmount = hourlyAmount,
                    calculationDetails = "${effectiveHourlyRate.amountInCents / 100.0} PLN/h ($rateLabel) × ${totalHoursWorked.toPlainString()} h = ${hourlyAmount.amountInCents / 100.0} PLN"
                )
            )
        }

        // ── Recurring compensation components ─────────────────────────────────

        for (component in compensationConfig.components.map { it.toDomain() }.filter { it.isActive }) {
            val calculated: Money = when (component.type) {
                ComponentType.FIXED ->
                    // value is stored as full PLN, convert to grosz
                    Money.fromCents(component.value.movePointRight(2).toLong())

                ComponentType.PERCENTAGE_OF_REVENUE -> {
                    val base: Money? = when (component.calculationBase) {
                        CalculationBase.GROSS_REVENUE -> revenueGross
                        CalculationBase.NET_REVENUE -> revenueNet
                        else -> null
                    }
                    if (base == null) {
                        // Revenue not provided – skip, log in details
                        breakdowns.add(
                            PayrollComponentBreakdown(
                                componentName = component.name,
                                calculatedAmount = Money.ZERO,
                                calculationDetails = "POMINIĘTO – brak danych o przychodzie (${component.calculationBase?.name}). Podaj przychód przy generowaniu listy płac."
                            )
                        )
                        continue
                    }
                    val amount = (base.amountInCents.toBigDecimal() * component.value / BigDecimal("100"))
                        .setScale(0, RoundingMode.HALF_UP).toLong()
                    Money.fromCents(amount)
                }

                ComponentType.HOURLY -> {
                    // component.value is the bonus rate per hour (in PLN)
                    val amount = (totalHoursWorked * component.value.movePointRight(2))
                        .setScale(0, RoundingMode.HALF_UP).toLong()
                    Money.fromCents(amount)
                }

                ComponentType.BONUS -> {
                    // component.value is a percentage of base salary
                    val base = baseSalaryGross.amountInCents.toBigDecimal()
                    val amount = (base * component.value / BigDecimal("100"))
                        .setScale(0, RoundingMode.HALF_UP).toLong()
                    Money.fromCents(amount)
                }
            }

            val details = when (component.type) {
                ComponentType.FIXED ->
                    "Stały dodatek: ${component.value.toPlainString()} PLN"
                ComponentType.PERCENTAGE_OF_REVENUE -> {
                    val baseAmount = when (component.calculationBase) {
                        CalculationBase.GROSS_REVENUE -> revenueGross
                        CalculationBase.NET_REVENUE -> revenueNet
                        else -> null
                    }
                    "${component.value.toPlainString()}% × ${baseAmount?.amountInCents?.div(100.0)} PLN (${component.calculationBase?.name}) = ${calculated.amountInCents / 100.0} PLN"
                }
                ComponentType.HOURLY ->
                    "${component.value.toPlainString()} PLN/h × ${totalHoursWorked.toPlainString()} h = ${calculated.amountInCents / 100.0} PLN"
                ComponentType.BONUS ->
                    "${component.value.toPlainString()}% × ${baseSalaryGross.amountInCents / 100.0} PLN (podstawa) = ${calculated.amountInCents / 100.0} PLN"
            }

            if (calculated.amountInCents != 0L) {
                breakdowns.add(
                    PayrollComponentBreakdown(
                        componentName = component.name,
                        calculatedAmount = calculated,
                        calculationDetails = details
                    )
                )
            }
        }

        // ── Ad-hoc bonus entries ──────────────────────────────────────────────

        val payrollId = PayrollEntryId.random()

        for (bonusEntity in pendingBonuses) {
            breakdowns.add(
                PayrollComponentBreakdown(
                    componentName = bonusEntity.name,
                    calculatedAmount = Money.fromCents(bonusEntity.amountCents),
                    calculationDetails = if (bonusEntity.amountCents >= 0)
                        "Jednorazowy bonus/dodatek: ${bonusEntity.amountCents / 100.0} PLN"
                    else
                        "Jednorazowe potrącenie: ${bonusEntity.amountCents / 100.0} PLN"
                )
            )
            // Mark bonus as included
            bonusEntity.status = BonusEntryStatus.INCLUDED_IN_PAYROLL
            bonusEntity.payrollEntryId = payrollId.value
            bonusEntity.updatedAt = Instant.now()
            bonusRepository.save(bonusEntity)
        }

        // ── Totals ────────────────────────────────────────────────────────────

        val componentTotal = breakdowns.fold(Money.ZERO) { acc, b -> acc.plus(b.calculatedAmount) }
        val totalGross = baseSalaryGross.plus(componentTotal)

        val payrollEntry = PayrollEntry(
            id = payrollId,
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
                FieldChange("totalHours", null, totalHoursWorked.toPlainString()),
                FieldChange("bonusEntriesIncluded", null, pendingBonuses.size.toString()),
                FieldChange("revenueGrossCents", null, command.revenueGrossCents?.toString()),
                FieldChange("revenueNetCents", null, command.revenueNetCents?.toString())
            )
        ))

        payrollEntry.id
    }
}
