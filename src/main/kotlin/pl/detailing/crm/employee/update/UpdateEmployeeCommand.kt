package pl.detailing.crm.employee.update

import pl.detailing.crm.shared.*

data class UpdateEmployeeCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val employeeId: EmployeeId,
    val firstName: String,
    val lastName: String,
    val phone: String?,
    val email: String?
)
