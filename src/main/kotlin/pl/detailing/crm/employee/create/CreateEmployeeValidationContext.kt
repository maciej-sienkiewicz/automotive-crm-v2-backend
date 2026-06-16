package pl.detailing.crm.employee.create

import pl.detailing.crm.shared.StudioId

data class CreateEmployeeValidationContext(
    val studioId: StudioId,
    val firstName: String,
    val lastName: String,
    val email: String?,
    val emailAlreadyExists: Boolean
)
