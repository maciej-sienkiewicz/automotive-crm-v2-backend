package pl.detailing.crm.employee.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.employee.domain.LeaveRequest
import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(
    name = "leave_requests",
    indexes = [
        Index(name = "idx_leave_studio_employee", columnList = "studio_id, employee_id"),
        Index(name = "idx_leave_studio_status", columnList = "studio_id, status"),
        Index(name = "idx_leave_studio_dates", columnList = "studio_id, start_date, end_date"),
        Index(name = "idx_leave_employee", columnList = "employee_id")
    ]
)
class LeaveRequestEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "employee_id", nullable = false, columnDefinition = "uuid")
    val employeeId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false, length = 20)
    var leaveType: LeaveType,

    @Column(name = "start_date", nullable = false)
    var startDate: LocalDate,

    @Column(name = "end_date", nullable = false)
    var endDate: LocalDate,

    @Column(name = "business_days_count", nullable = false)
    var businessDaysCount: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: LeaveStatus,

    @Column(name = "reason", columnDefinition = "text")
    var reason: String?,

    @Column(name = "reviewed_by", columnDefinition = "uuid")
    var reviewedBy: UUID?,

    @Column(name = "reviewed_at", columnDefinition = "timestamp with time zone")
    var reviewedAt: Instant?,

    @Column(name = "review_note", columnDefinition = "text")
    var reviewNote: String?,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): LeaveRequest = LeaveRequest(
        id = LeaveRequestId(id),
        studioId = StudioId(studioId),
        employeeId = EmployeeId(employeeId),
        leaveType = leaveType,
        startDate = startDate,
        endDate = endDate,
        businessDaysCount = businessDaysCount,
        status = status,
        reason = reason,
        reviewedBy = reviewedBy?.let { UserId(it) },
        reviewedAt = reviewedAt,
        reviewNote = reviewNote,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(request: LeaveRequest): LeaveRequestEntity = LeaveRequestEntity(
            id = request.id.value,
            studioId = request.studioId.value,
            employeeId = request.employeeId.value,
            leaveType = request.leaveType,
            startDate = request.startDate,
            endDate = request.endDate,
            businessDaysCount = request.businessDaysCount,
            status = request.status,
            reason = request.reason,
            reviewedBy = request.reviewedBy?.value,
            reviewedAt = request.reviewedAt,
            reviewNote = request.reviewNote,
            createdAt = request.createdAt,
            updatedAt = request.updatedAt
        )
    }
}
