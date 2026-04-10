package pl.detailing.crm.employee.terminate

import pl.detailing.crm.shared.*
import java.time.LocalDate

data class TerminateEmployeeCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val employeeId: EmployeeId,
    val terminationDate: LocalDate,
    val reason: String?
)
