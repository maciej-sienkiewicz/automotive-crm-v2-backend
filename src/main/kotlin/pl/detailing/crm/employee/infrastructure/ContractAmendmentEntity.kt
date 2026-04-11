package pl.detailing.crm.employee.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.employee.domain.ContractAmendment
import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(
    name = "contract_amendments",
    indexes = [
        Index(name = "idx_amendments_contract", columnList = "contract_id"),
        Index(name = "idx_amendments_employee", columnList = "employee_id"),
        Index(name = "idx_amendments_effective", columnList = "contract_id, effective_from")
    ]
)
class ContractAmendmentEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "employee_id", nullable = false, columnDefinition = "uuid")
    val employeeId: UUID,

    @Column(name = "contract_id", nullable = false, columnDefinition = "uuid")
    val contractId: UUID,

    @Column(name = "effective_from", nullable = false)
    var effectiveFrom: LocalDate,

    @Column(name = "effective_to")
    var effectiveTo: LocalDate?,

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_mode", nullable = false, length = 20)
    var employmentMode: EmploymentMode,

    @Enumerated(EnumType.STRING)
    @Column(name = "etat_fraction", length = 20)
    var etatFraction: EtatFraction?,

    @Column(name = "monthly_salary_gross")
    var monthlySalaryGross: Long?,

    @Column(name = "hourly_rate_gross")
    var hourlyRateGross: Long?,

    @Column(name = "hourly_rate_net")
    var hourlyRateNet: Long?,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
) {
    fun toDomain(): ContractAmendment = ContractAmendment(
        id = ContractAmendmentId(id),
        studioId = StudioId(studioId),
        employeeId = EmployeeId(employeeId),
        contractId = EmploymentContractId(contractId),
        effectiveFrom = effectiveFrom,
        effectiveTo = effectiveTo,
        employmentMode = employmentMode,
        etatFraction = etatFraction,
        monthlySalaryGross = monthlySalaryGross?.let { Money.fromCents(it) },
        hourlyRateGross = hourlyRateGross?.let { Money.fromCents(it) },
        hourlyRateNet = hourlyRateNet?.let { Money.fromCents(it) },
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(amendment: ContractAmendment): ContractAmendmentEntity = ContractAmendmentEntity(
            id = amendment.id.value,
            studioId = amendment.studioId.value,
            employeeId = amendment.employeeId.value,
            contractId = amendment.contractId.value,
            effectiveFrom = amendment.effectiveFrom,
            effectiveTo = amendment.effectiveTo,
            employmentMode = amendment.employmentMode,
            etatFraction = amendment.etatFraction,
            monthlySalaryGross = amendment.monthlySalaryGross?.amountInCents,
            hourlyRateGross = amendment.hourlyRateGross?.amountInCents,
            hourlyRateNet = amendment.hourlyRateNet?.amountInCents,
            createdAt = amendment.createdAt
        )
    }
}
