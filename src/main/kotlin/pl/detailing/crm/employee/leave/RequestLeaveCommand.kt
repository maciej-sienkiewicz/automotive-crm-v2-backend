package pl.detailing.crm.employee.leave

import pl.detailing.crm.shared.*
import java.time.LocalDate

data class RequestLeaveCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val employeeId: EmployeeId,
    val leaveType: LeaveType,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val reason: String?
)
