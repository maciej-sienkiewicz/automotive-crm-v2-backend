package pl.detailing.crm.employee.create

import pl.detailing.crm.shared.EmployeeId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class CreateEmployeeCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val firstName: String,
    val lastName: String,
    val phone: String?,
    val email: String?,
    val createAccount: Boolean = false
)
