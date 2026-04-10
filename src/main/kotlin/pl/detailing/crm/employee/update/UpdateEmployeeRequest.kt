package pl.detailing.crm.employee.update

import java.time.LocalDate

data class UpdateEmployeeRequest(
    val linkedUserId: String?,
    val firstName: String,
    val lastName: String,
    val phone: String?,
    val email: String?,
    val personalEmail: String?,
    val pesel: String?,
    val nip: String?,
    val addressStreet: String?,
    val addressCity: String?,
    val addressPostalCode: String?,
    val position: String,
    val hireDate: LocalDate,
    val notes: String?
)
