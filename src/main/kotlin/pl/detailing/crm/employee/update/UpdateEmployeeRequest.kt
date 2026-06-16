package pl.detailing.crm.employee.update

data class UpdateEmployeeRequest(
    val firstName: String,
    val lastName: String,
    val phone: String?,
    val email: String?
)
