package pl.detailing.crm.employee.create

import pl.detailing.crm.shared.UserRole
import java.time.LocalDate

data class CreateEmployeeRequest(
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
    val notes: String?,
    /** When true, a user account is created and an invitation email is sent to [accountEmail]. */
    val createAccount: Boolean = false,
    val accountEmail: String? = null,
    val accountRole: UserRole? = null
)
