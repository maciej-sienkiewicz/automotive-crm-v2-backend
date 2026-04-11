package pl.detailing.crm.employee.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.employee.domain.BonusEntry
import pl.detailing.crm.employee.domain.BonusEntryStatus
import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

@Entity
@Table(
    name = "bonus_entries",
    indexes = [
        Index(name = "idx_bonus_employee_period", columnList = "employee_id, period"),
        Index(name = "idx_bonus_studio_period", columnList = "studio_id, period"),
        Index(name = "idx_bonus_payroll", columnList = "payroll_entry_id")
    ]
)
class BonusEntryEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "employee_id", nullable = false, columnDefinition = "uuid")
    val employeeId: UUID,

    /** Stored as "YYYY-MM" string for simple querying */
    @Column(name = "period", nullable = false, length = 7)
    var period: String,

    @Column(name = "name", nullable = false, length = 300)
    var name: String,

    /** Amount in grosz (1/100 PLN). Positive = bonus, negative = deduction. */
    @Column(name = "amount_cents", nullable = false)
    var amountCents: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: BonusEntryStatus,

    @Column(name = "payroll_entry_id", columnDefinition = "uuid")
    var payrollEntryId: UUID?,

    @Column(name = "notes", columnDefinition = "text")
    var notes: String?,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): BonusEntry = BonusEntry(
        id = BonusEntryId(id),
        studioId = StudioId(studioId),
        employeeId = EmployeeId(employeeId),
        period = YearMonth.parse(period),
        name = name,
        amountCents = amountCents,
        status = status,
        payrollEntryId = payrollEntryId?.let { PayrollEntryId(it) },
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(b: BonusEntry): BonusEntryEntity = BonusEntryEntity(
            id = b.id.value,
            studioId = b.studioId.value,
            employeeId = b.employeeId.value,
            period = b.period.toString(),
            name = b.name,
            amountCents = b.amountCents,
            status = b.status,
            payrollEntryId = b.payrollEntryId?.value,
            notes = b.notes,
            createdAt = b.createdAt,
            updatedAt = b.updatedAt
        )
    }
}
