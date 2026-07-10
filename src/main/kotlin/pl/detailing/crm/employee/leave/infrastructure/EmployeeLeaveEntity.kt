package pl.detailing.crm.employee.leave.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.employee.leave.domain.EmployeeLeave
import pl.detailing.crm.employee.leave.domain.LeaveType
import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(
    name = "employee_leaves",
    indexes = [
        Index(name = "idx_employee_leaves_studio_id", columnList = "studio_id"),
        Index(name = "idx_employee_leaves_studio_employee", columnList = "studio_id, employee_id"),
        Index(name = "idx_employee_leaves_studio_dates", columnList = "studio_id, start_date, end_date")
    ]
)
class EmployeeLeaveEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "employee_id", nullable = false, columnDefinition = "uuid")
    val employeeId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false, length = 30)
    var leaveType: LeaveType,

    @Column(name = "start_date", nullable = false)
    var startDate: LocalDate,

    @Column(name = "end_date", nullable = false)
    var endDate: LocalDate,

    @Column(name = "note", length = 500)
    var note: String?,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
) {
    fun toDomain(): EmployeeLeave = EmployeeLeave(
        id = id,
        studioId = StudioId(studioId),
        employeeId = EmployeeId(employeeId),
        leaveType = leaveType,
        startDate = startDate,
        endDate = endDate,
        note = note,
        createdBy = UserId(createdBy),
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(leave: EmployeeLeave): EmployeeLeaveEntity = EmployeeLeaveEntity(
            id = leave.id,
            studioId = leave.studioId.value,
            employeeId = leave.employeeId.value,
            leaveType = leave.leaveType,
            startDate = leave.startDate,
            endDate = leave.endDate,
            note = leave.note,
            createdBy = leave.createdBy.value,
            createdAt = leave.createdAt
        )
    }
}
