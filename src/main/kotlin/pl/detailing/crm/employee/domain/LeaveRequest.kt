package pl.detailing.crm.employee.domain

import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.LocalDate

data class LeaveRequest(
    val id: LeaveRequestId,
    val studioId: StudioId,
    val employeeId: EmployeeId,
    val leaveType: LeaveType,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val businessDaysCount: Int,
    val status: LeaveStatus,
    val reason: String?,
    val reviewedBy: UserId?,
    val reviewedAt: Instant?,
    val reviewNote: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)
