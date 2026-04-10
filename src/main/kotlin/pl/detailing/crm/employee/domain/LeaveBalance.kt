package pl.detailing.crm.employee.domain

import pl.detailing.crm.shared.*
import java.time.Instant

data class LeaveBalance(
    val id: LeaveBalanceId,
    val studioId: StudioId,
    val employeeId: EmployeeId,
    val year: Int,
    val totalDays: Int,
    val usedDays: Int,
    val pendingDays: Int,
    val carriedOverDays: Int,
    val adjustmentDays: Int,
    val notes: String?,
    val updatedAt: Instant
) {
    fun remainingDays(): Int = totalDays + carriedOverDays + adjustmentDays - usedDays - pendingDays
}
