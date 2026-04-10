package pl.detailing.crm.employee.create

import pl.detailing.crm.shared.*
import java.time.LocalDate

data class CreateEmployeeCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val linkedUserId: UserId?,
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
