package pl.detailing.crm.employee.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.employee.domain.LeaveBalance
import pl.detailing.crm.shared.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "leave_balances",
    indexes = [
        Index(name = "idx_leave_balance_studio_employee_year", columnList = "studio_id, employee_id, year", unique = true),
        Index(name = "idx_leave_balance_employee", columnList = "employee_id")
    ]
)
class LeaveBalanceEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "employee_id", nullable = false, columnDefinition = "uuid")
    val employeeId: UUID,

    @Column(name = "year", nullable = false)
    var year: Int,

    @Column(name = "total_days", nullable = false)
    var totalDays: Int,

    @Column(name = "used_days", nullable = false)
    var usedDays: Int,

    @Column(name = "pending_days", nullable = false)
    var pendingDays: Int,

    @Column(name = "carried_over_days", nullable = false)
    var carriedOverDays: Int,

    @Column(name = "adjustment_days", nullable = false)
    var adjustmentDays: Int,

    @Column(name = "notes", columnDefinition = "text")
    var notes: String?,

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): LeaveBalance = LeaveBalance(
        id = LeaveBalanceId(id),
        studioId = StudioId(studioId),
        employeeId = EmployeeId(employeeId),
        year = year,
        totalDays = totalDays,
        usedDays = usedDays,
        pendingDays = pendingDays,
        carriedOverDays = carriedOverDays,
        adjustmentDays = adjustmentDays,
        notes = notes,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(balance: LeaveBalance): LeaveBalanceEntity = LeaveBalanceEntity(
            id = balance.id.value,
            studioId = balance.studioId.value,
            employeeId = balance.employeeId.value,
            year = balance.year,
            totalDays = balance.totalDays,
            usedDays = balance.usedDays,
            pendingDays = balance.pendingDays,
            carriedOverDays = balance.carriedOverDays,
            adjustmentDays = balance.adjustmentDays,
            notes = balance.notes,
            updatedAt = balance.updatedAt
        )
    }
}
