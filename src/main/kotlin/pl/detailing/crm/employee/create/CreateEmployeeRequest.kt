package pl.detailing.crm.employee.create

data class CreateEmployeeRequest(
    val firstName: String,
    val lastName: String,
    val phone: String?,
    val email: String?,
    val createAccount: Boolean = false,
    val roleId: String? = null
)
