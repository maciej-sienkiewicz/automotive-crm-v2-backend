package pl.detailing.crm.employee.domain

import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.YearMonth

/**
 * Ad-hoc one-time bonus or deduction for a specific payroll period.
 *
 * Unlike [CompensationComponent] (which is a recurring rule in the compensation config),
 * a [BonusEntry] is an explicit per-period entry:
 * - positive [amountCents]  → bonus / additional payment
 * - negative [amountCents]  → deduction / correction
 *
 * Status lifecycle:
 *  PENDING           → added, not yet included in any payroll run
 *  INCLUDED_IN_PAYROLL → referenced by a generated (DRAFT or later) [PayrollEntry]
 */
data class BonusEntry(
    val id: BonusEntryId,
    val studioId: StudioId,
    val employeeId: EmployeeId,
    /** Payroll period this bonus belongs to, e.g. "2024-03" */
    val period: YearMonth,
    val name: String,
    /** Positive = bonus, negative = deduction. Stored in grosz (1/100 PLN). */
    val amountCents: Long,
    val status: BonusEntryStatus,
    /** Optional reference to the payroll entry that included this bonus */
    val payrollEntryId: PayrollEntryId?,
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)

enum class BonusEntryStatus {
    PENDING,
    INCLUDED_IN_PAYROLL
}
