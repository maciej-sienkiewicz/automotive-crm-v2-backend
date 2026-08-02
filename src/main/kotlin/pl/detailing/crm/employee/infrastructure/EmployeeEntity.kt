package pl.detailing.crm.employee.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.employee.domain.Employee
import pl.detailing.crm.shared.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "employees",
    indexes = [
        Index(name = "idx_employees_studio_id", columnList = "studio_id"),
        Index(name = "idx_employees_studio_user", columnList = "studio_id, user_id"),
        Index(name = "idx_employees_studio_email", columnList = "studio_id, email"),
        Index(name = "idx_employees_created_by", columnList = "created_by")
    ]
)
class EmployeeEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "user_id", columnDefinition = "uuid")
    var userId: UUID?,

    @Column(name = "first_name", nullable = false, length = 100)
    var firstName: String,

    @Column(name = "last_name", nullable = false, length = 100)
    var lastName: String,

    @Column(name = "phone", length = 30)
    var phone: String?,

    @Column(name = "email", length = 255)
    var email: String?,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "updated_by", nullable = false, columnDefinition = "uuid")
    var updatedBy: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): Employee = Employee(
        id = EmployeeId(id),
        studioId = StudioId(studioId),
        userId = userId?.let { UserId(it) },
        firstName = firstName,
        lastName = lastName,
        phone = phone,
        email = email,
        createdBy = UserId(createdBy),
        updatedBy = UserId(updatedBy),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(employee: Employee): EmployeeEntity = EmployeeEntity(
            id = employee.id.value,
            studioId = employee.studioId.value,
            userId = employee.userId?.value,
            firstName = employee.firstName,
            lastName = employee.lastName,
            phone = employee.phone,
            email = employee.email,
            createdBy = employee.createdBy.value,
            updatedBy = employee.updatedBy.value,
            createdAt = employee.createdAt,
            updatedAt = employee.updatedAt
        )
    }
}
