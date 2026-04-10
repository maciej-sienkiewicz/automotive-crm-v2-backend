package pl.detailing.crm.employee.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.employee.domain.WorkTimeEntry
import pl.detailing.crm.shared.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Entity
@Table(
    name = "work_time_entries",
    indexes = [
        Index(name = "idx_worktime_studio_employee_date", columnList = "studio_id, employee_id, date"),
        Index(name = "idx_worktime_studio_date", columnList = "studio_id, date"),
        Index(name = "idx_worktime_studio_status", columnList = "studio_id, status"),
        Index(name = "idx_worktime_employee", columnList = "employee_id")
    ]
)
class WorkTimeEntryEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "employee_id", nullable = false, columnDefinition = "uuid")
    val employeeId: UUID,

    @Column(name = "date", nullable = false)
    var date: LocalDate,

    @Column(name = "start_time", nullable = false)
    var startTime: LocalTime,

    @Column(name = "end_time", nullable = false)
    var endTime: LocalTime,

    @Column(name = "break_minutes", nullable = false)
    var breakMinutes: Int,

    @Column(name = "effective_hours", nullable = false, precision = 6, scale = 2)
    var effectiveHours: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20)
    var entryType: WorkTimeEntryType,

    @Column(name = "overtime_multiplier", nullable = false, precision = 4, scale = 2)
    var overtimeMultiplier: BigDecimal,

    @Column(name = "notes", columnDefinition = "text")
    var notes: String?,

    @Column(name = "approved_by", columnDefinition = "uuid")
    var approvedBy: UUID?,

    @Column(name = "approved_at", columnDefinition = "timestamp with time zone")
    var approvedAt: Instant?,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: WorkTimeStatus,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): WorkTimeEntry = WorkTimeEntry(
        id = WorkTimeEntryId(id),
        studioId = StudioId(studioId),
        employeeId = EmployeeId(employeeId),
        date = date,
        startTime = startTime,
        endTime = endTime,
        breakMinutes = breakMinutes,
        effectiveHours = effectiveHours,
        entryType = entryType,
        overtimeMultiplier = overtimeMultiplier,
        notes = notes,
        approvedBy = approvedBy?.let { UserId(it) },
        approvedAt = approvedAt,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(entry: WorkTimeEntry): WorkTimeEntryEntity = WorkTimeEntryEntity(
            id = entry.id.value,
            studioId = entry.studioId.value,
            employeeId = entry.employeeId.value,
            date = entry.date,
            startTime = entry.startTime,
            endTime = entry.endTime,
            breakMinutes = entry.breakMinutes,
            effectiveHours = entry.effectiveHours,
            entryType = entry.entryType,
            overtimeMultiplier = entry.overtimeMultiplier,
            notes = entry.notes,
            approvedBy = entry.approvedBy?.value,
            approvedAt = entry.approvedAt,
            status = entry.status,
            createdAt = entry.createdAt,
            updatedAt = entry.updatedAt
        )
    }
}
