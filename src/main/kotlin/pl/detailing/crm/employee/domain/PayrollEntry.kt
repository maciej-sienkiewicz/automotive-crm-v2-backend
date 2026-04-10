package pl.detailing.crm.employee.domain

import pl.detailing.crm.shared.*
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth

data class PayrollEntry(
    val id: PayrollEntryId,
    val studioId: StudioId,
    val employeeId: EmployeeId,
    val contractId: EmploymentContractId,
    val period: YearMonth,
    val baseSalaryGross: Money,
    val totalHoursWorked: BigDecimal,
    val componentBreakdown: List<PayrollComponentBreakdown>,
    val totalGross: Money,
    val totalNet: Money?,
    val employerCostTotal: Money?,
    val status: PayrollStatus,
    val notes: String?,
    val confirmedBy: UserId?,
    val confirmedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class PayrollComponentBreakdown(
    val componentName: String,
    val calculatedAmount: Money,
    val calculationDetails: String
)
