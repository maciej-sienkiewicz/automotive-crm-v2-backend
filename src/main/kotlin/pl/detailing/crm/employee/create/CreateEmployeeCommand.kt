package pl.detailing.crm.employee.create

import pl.detailing.crm.shared.*

data class CreateEmployeeCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val firstName: String,
    val lastName: String,
    val phone: String?,
    val email: String?
)
