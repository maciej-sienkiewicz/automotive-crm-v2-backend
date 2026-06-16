package pl.detailing.crm.employee.create

import pl.detailing.crm.shared.UserRole

data class CreateEmployeeRequest(
    val firstName: String,
    val lastName: String,
    val phone: String?,
    val email: String?,
    val createAccount: Boolean = false,
    val accountEmail: String? = null,
    val accountRole: UserRole? = null
)
