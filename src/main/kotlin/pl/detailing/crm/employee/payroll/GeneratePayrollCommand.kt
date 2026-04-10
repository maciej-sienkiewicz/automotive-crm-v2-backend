package pl.detailing.crm.employee.payroll

import pl.detailing.crm.shared.*
import java.time.YearMonth

data class GeneratePayrollCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val employeeId: EmployeeId,
    val period: YearMonth,
    val notes: String?
)
