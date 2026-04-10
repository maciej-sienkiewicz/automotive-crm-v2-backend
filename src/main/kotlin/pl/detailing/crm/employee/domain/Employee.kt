package pl.detailing.crm.employee.domain

import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.LocalDate

data class Employee(
    val id: EmployeeId,
    val studioId: StudioId,
    val userId: UserId?,
    val firstName: String,
    val lastName: String,
    val phone: String?,
    val email: String?,
    val personalEmail: String?,
    val pesel: String?,
    val nip: String?,
    val address: EmployeeAddress?,
    val position: String,
    val hireDate: LocalDate,
    val terminationDate: LocalDate?,
    val status: EmployeeStatus,
    val notes: String?,
    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    fun fullName(): String = "$firstName $lastName"
}

data class EmployeeAddress(
    val street: String,
    val city: String,
    val postalCode: String
)
