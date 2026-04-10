package pl.detailing.crm.employee.create

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class CreateEmployeeValidationContext(
    val studioId: StudioId,
    val firstName: String,
    val lastName: String,
    val email: String?,
    val linkedUserId: UserId?,
    val emailAlreadyExists: Boolean,
    val linkedUserExists: Boolean,
    val linkedUserAlreadyAssigned: Boolean
)
