package pl.detailing.crm.employee.create

import pl.detailing.crm.shared.*
import java.time.LocalDate

data class CreateEmployeeCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
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
    val hireDate: LocalDate? = null,
    val notes: String? = null,
    val linkedUserId: UserId? = null
)
