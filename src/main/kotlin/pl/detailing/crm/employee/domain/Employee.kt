package pl.detailing.crm.employee.domain

import pl.detailing.crm.shared.*
import java.time.Instant

data class Employee(
    val id: EmployeeId,
    val studioId: StudioId,
    val userId: UserId?,
    val firstName: String,
    val lastName: String,
    val phone: String?,
    val email: String?,
    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    fun fullName(): String = "$firstName $lastName"
}
