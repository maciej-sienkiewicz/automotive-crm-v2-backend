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

        // Approved work time entries for the period, grouped by entry type
        val from = command.period.atDay(1)
        val to = command.period.atEndOfMonth()
        val workTimeEntries = workTimeRepository.findByEmployeeIdAndDateRange(
            command.employeeId.value, command.studioId.value, from, to
        ).filter { it.status == WorkTimeStatus.APPROVED }.map { it.toDomain() }

        val byType: Map<WorkTimeEntryType, List<pl.detailing.crm.employee.domain.WorkTimeEntry>> =
            workTimeEntries.groupBy { it.entryType }

        val regularHours = byType[WorkTimeEntryType.REGULAR]
            ?.fold(BigDecimal.ZERO) { acc, e -> acc + e.effectiveHours } ?: BigDecimal.ZERO
        val totalHoursWorked = workTimeEntries.fold(BigDecimal.ZERO) { acc, e -> acc + e.effectiveHours }

        // Pending bonus entries for the period
        val pendingBonuses = bonusRepository.findByEmployeeIdAndStudioIdAndPeriodOrderByCreatedAtAsc(
            command.employeeId.value, command.studioId.value, command.period.toString()
        ).filter { it.status == BonusEntryStatus.PENDING }

        // Resolve compensation config
        val config = compensationConfig.toDomain()
        val baseSalaryGross = config.baseSalaryGross ?: config.monthlySalaryGross ?: Money.ZERO
        val hourlyRateGross = config.hourlyRateGross
        val hourlyRateNet = config.hourlyRateNet

        // Revenue inputs (for PERCENTAGE_OF_REVENUE components)
        val revenueGross = command.revenueGrossCents?.let { Money.fromCents(it) }
        val revenueNet = command.revenueNetCents?.let { Money.fromCents(it) }

        val breakdowns = mutableListOf<PayrollComponentBreakdown>()

        // ── Base pay ──────────────────────────────────────────────────────────

        val effectiveHourlyRate = hourlyRateGross ?: hourlyRateNet
        if (effectiveHourlyRate != null) {
            // HOURLY mode (UZ / B2B): each work-time entry type is billed at its own multiplier
            val rateLabel = if (hourlyRateNet != null) "netto" else "brutto"

            for (type in WorkTimeEntryType.entries.sortedBy { it.ordinal }) {
                val entries = byType[type] ?: continue
                val hours = entries.fold(BigDecimal.ZERO) { acc, e -> acc + e.effectiveHours }
                if (hours <= BigDecimal.ZERO) continue

                // Use the multiplier stored on the first entry (consistent across same-type entries)
                val multiplier = entries.first().overtimeMultiplier
                val amount = Money.fromCents(
                    (effectiveHourlyRate.amountInCents.toBigDecimal() * hours * multiplier)
                        .setScale(0, RoundingMode.HALF_UP).toLong()
                )
                val multiplierSuffix = if (multiplier.compareTo(BigDecimal.ONE) == 0) ""
                                       else " × ${multiplier.stripTrailingZeros().toPlainString()}"
                breakdowns.add(
                    PayrollComponentBreakdown(
                        componentName = entryTypeLabel(type),
                        calculatedAmount = amount,
                        calculationDetails = "${effectiveHourlyRate.amountInCents / 100.0} PLN/h" +
                            " ($rateLabel)$multiplierSuffix × ${hours.toPlainString()} h" +
                            " = ${amount.amountInCents / 100.0} PLN"
                    )
                )
            }
        } else if (config.monthlySalaryGross != null) {
            // SALARY mode (UOP): fixed monthly salary + overtime premium for non-REGULAR entries
            val standardHours = config.etatFraction.standardMonthlyHours()
            val baseHourlyRate = config.monthlySalaryGross!!.amountInCents.toBigDecimal()
                .divide(standardHours.toBigDecimal(), 4, RoundingMode.HALF_UP)

            for (type in WorkTimeEntryType.entries.filter { it != WorkTimeEntryType.REGULAR }
                         .sortedBy { it.ordinal }) {
                val entries = byType[type] ?: continue
                val hours = entries.fold(BigDecimal.ZERO) { acc, e -> acc + e.effectiveHours }
                if (hours <= BigDecimal.ZERO) continue

                val multiplier = entries.first().overtimeMultiplier
                val premiumMultiplier = multiplier.subtract(BigDecimal.ONE) // e.g. 0.5 for OVERTIME_150
                if (premiumMultiplier <= BigDecimal.ZERO) continue

                val premiumAmount = (baseHourlyRate * hours * premiumMultiplier)
                    .setScale(0, RoundingMode.HALF_UP).toLong()
                if (premiumAmount <= 0L) continue

                val amount = Money.fromCents(premiumAmount)
                breakdowns.add(
                    PayrollComponentBreakdown(
                        componentName = "${entryTypeLabel(type)} – dodatek",
                        calculatedAmount = amount,
                        calculationDetails = "${baseHourlyRate.setScale(2, RoundingMode.HALF_UP)} PLN/h" +
                            " × ${hours.toPlainString()} h" +
                            " × ${premiumMultiplier.stripTrailingZeros().toPlainString()}" +
                            " = ${amount.amountInCents / 100.0} PLN"
                    )
                )
            }
        }

        // ── Recurring compensation components ─────────────────────────────────

        for (component in compensationConfig.components.map { it.toDomain() }.filter { it.isActive }) {
            val calculated: Money = when (component.type) {
                ComponentType.FIXED ->
                    Money.fromCents(component.value.movePointRight(2).toLong())

                ComponentType.PERCENTAGE_OF_REVENUE -> {
                    val base: Money? = when (component.calculationBase) {
                        CalculationBase.GROSS_REVENUE -> revenueGross
                        CalculationBase.NET_REVENUE -> revenueNet
                        else -> null
                    }
                    if (base == null) {
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
                    // Bonus rate per regular hour — benefit-type hours are already compensated via multipliers
                    val amount = (regularHours * component.value.movePointRight(2))
                        .setScale(0, RoundingMode.HALF_UP).toLong()
                    Money.fromCents(amount)
                }

                ComponentType.BONUS -> {
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
                    "${component.value.toPlainString()}% × ${baseAmount?.amountInCents?.div(100.0)} PLN" +
                        " (${component.calculationBase?.name}) = ${calculated.amountInCents / 100.0} PLN"
                }
                ComponentType.HOURLY ->
                    "${component.value.toPlainString()} PLN/h × ${regularHours.toPlainString()} h (regularne)" +
                        " = ${calculated.amountInCents / 100.0} PLN"
                ComponentType.BONUS ->
                    "${component.value.toPlainString()}% × ${baseSalaryGross.amountInCents / 100.0} PLN" +
                        " (podstawa) = ${calculated.amountInCents / 100.0} PLN"
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
            regularHoursWorked = regularHours,
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

        auditService.log(
            LogAuditCommand(
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
                    FieldChange("regularHours", null, regularHours.toPlainString()),
                    FieldChange("bonusEntriesIncluded", null, pendingBonuses.size.toString()),
                    FieldChange("revenueGrossCents", null, command.revenueGrossCents?.toString()),
                    FieldChange("revenueNetCents", null, command.revenueNetCents?.toString())
                )
            )
        )

        payrollEntry.id
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun entryTypeLabel(type: WorkTimeEntryType): String = when (type) {
        WorkTimeEntryType.REGULAR      -> "Praca regularna"
        WorkTimeEntryType.OVERTIME_150 -> "Nadgodziny 150%"
        WorkTimeEntryType.OVERTIME_200 -> "Nadgodziny 200%"
        WorkTimeEntryType.NIGHT_WORK   -> "Praca nocna"
        WorkTimeEntryType.HOLIDAY_WORK -> "Praca w święto"
        WorkTimeEntryType.ON_CALL      -> "Dyżur"
    }

    /** Standard monthly hours based on employment fraction (used for SALARY overtime premium). */
    private fun EtatFraction?.standardMonthlyHours(): Int = when (this) {
        EtatFraction.FULL    -> 168
        EtatFraction.HALF    -> 84
        EtatFraction.QUARTER -> 42
        null                 -> 168
    }
}
