package pl.detailing.crm.employee.worktime

import pl.detailing.crm.shared.*
import java.time.LocalDate
import java.time.LocalTime

data class LogWorkTimeCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val employeeId: EmployeeId,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val breakMinutes: Int,
    val entryType: WorkTimeEntryType,
    val notes: String?
)
