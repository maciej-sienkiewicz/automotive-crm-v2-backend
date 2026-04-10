package pl.detailing.crm.employee.infrastructure

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.persistence.*
import pl.detailing.crm.employee.domain.PayrollComponentBreakdown
import pl.detailing.crm.employee.domain.PayrollEntry
import pl.detailing.crm.shared.*
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

@Entity
@Table(
    name = "payroll_entries",
    indexes = [
        Index(name = "idx_payroll_studio_employee", columnList = "studio_id, employee_id"),
        Index(name = "idx_payroll_studio_period", columnList = "studio_id, period"),
        Index(name = "idx_payroll_studio_status", columnList = "studio_id, status"),
        Index(name = "idx_payroll_contract", columnList = "contract_id")
    ]
)
class PayrollEntryEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "employee_id", nullable = false, columnDefinition = "uuid")
    val employeeId: UUID,

    @Column(name = "contract_id", nullable = false, columnDefinition = "uuid")
    val contractId: UUID,

    /** Stored as "YYYY-MM" string */
    @Column(name = "period", nullable = false, length = 7)
    var period: String,

    @Column(name = "base_salary_gross", nullable = false)
    var baseSalaryGross: Long,

    @Column(name = "total_hours_worked", nullable = false, precision = 8, scale = 2)
    var totalHoursWorked: BigDecimal,

    @Column(name = "component_breakdown_json", columnDefinition = "text")
    var componentBreakdownJson: String = "[]",

    @Column(name = "total_gross", nullable = false)
    var totalGross: Long,

    @Column(name = "total_net")
    var totalNet: Long?,

    @Column(name = "employer_cost_total")
    var employerCostTotal: Long?,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: PayrollStatus,

    @Column(name = "notes", columnDefinition = "text")
    var notes: String?,

    @Column(name = "confirmed_by", columnDefinition = "uuid")
    var confirmedBy: UUID?,

    @Column(name = "confirmed_at", columnDefinition = "timestamp with time zone")
    var confirmedAt: Instant?,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): PayrollEntry = PayrollEntry(
        id = PayrollEntryId(id),
        studioId = StudioId(studioId),
        employeeId = EmployeeId(employeeId),
        contractId = EmploymentContractId(contractId),
        period = YearMonth.parse(period),
        baseSalaryGross = Money.fromCents(baseSalaryGross),
        totalHoursWorked = totalHoursWorked,
        componentBreakdown = mapper.readValue<List<BreakdownJson>>(componentBreakdownJson).map { it.toDomain() },
        totalGross = Money.fromCents(totalGross),
        totalNet = totalNet?.let { Money.fromCents(it) },
        employerCostTotal = employerCostTotal?.let { Money.fromCents(it) },
        status = status,
        notes = notes,
        confirmedBy = confirmedBy?.let { UserId(it) },
        confirmedAt = confirmedAt,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        private val mapper = jacksonObjectMapper()

        fun fromDomain(entry: PayrollEntry): PayrollEntryEntity = PayrollEntryEntity(
            id = entry.id.value,
            studioId = entry.studioId.value,
            employeeId = entry.employeeId.value,
            contractId = entry.contractId.value,
            period = entry.period.toString(),
            baseSalaryGross = entry.baseSalaryGross.amountInCents,
            totalHoursWorked = entry.totalHoursWorked,
            componentBreakdownJson = mapper.writeValueAsString(
                entry.componentBreakdown.map { BreakdownJson.fromDomain(it) }
            ),
            totalGross = entry.totalGross.amountInCents,
            totalNet = entry.totalNet?.amountInCents,
            employerCostTotal = entry.employerCostTotal?.amountInCents,
            status = entry.status,
            notes = entry.notes,
            confirmedBy = entry.confirmedBy?.value,
            confirmedAt = entry.confirmedAt,
            createdAt = entry.createdAt,
            updatedAt = entry.updatedAt
        )
    }
}

private data class BreakdownJson(
    val componentName: String,
    val calculatedAmountCents: Long,
    val calculationDetails: String
) {
    fun toDomain(): PayrollComponentBreakdown = PayrollComponentBreakdown(
        componentName = componentName,
        calculatedAmount = Money.fromCents(calculatedAmountCents),
        calculationDetails = calculationDetails
    )

    companion object {
        fun fromDomain(b: PayrollComponentBreakdown): BreakdownJson = BreakdownJson(
            componentName = b.componentName,
            calculatedAmountCents = b.calculatedAmount.amountInCents,
            calculationDetails = b.calculationDetails
        )
    }
}
