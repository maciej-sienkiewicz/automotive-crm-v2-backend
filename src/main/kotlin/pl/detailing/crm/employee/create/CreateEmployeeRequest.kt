package pl.detailing.crm.employee.create

data class CreateEmployeeRequest(
    val firstName: String,
    val lastName: String,
    val phone: String?,
    val email: String?,
    val personalEmail: String? = null,
    val pesel: String? = null,
    val nip: String? = null,
    val addressStreet: String? = null,
    val addressCity: String? = null,
    val addressPostalCode: String? = null,
    val position: String = "",
    val hireDate: java.time.LocalDate? = null,
    val notes: String? = null,
    val linkedUserId: String? = null
)
