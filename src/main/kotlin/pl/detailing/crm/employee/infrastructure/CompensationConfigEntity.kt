package pl.detailing.crm.employee.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.employee.domain.CompensationConfig
import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(
    name = "compensation_configs",
    indexes = [
        Index(name = "idx_comp_config_studio_employee", columnList = "studio_id, employee_id"),
        Index(name = "idx_comp_config_contract", columnList = "contract_id"),
        Index(name = "idx_comp_config_effective", columnList = "studio_id, employee_id, effective_from")
    ]
)
class CompensationConfigEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "employee_id", nullable = false, columnDefinition = "uuid")
    val employeeId: UUID,

    @Column(name = "contract_id", nullable = false, columnDefinition = "uuid")
    var contractId: UUID,

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

    /** Monthly gross salary in cents – for UOP / UZ SALARY contracts */
    @Column(name = "monthly_salary_gross")
    var monthlySalaryGross: Long?,

    /** Base salary in cents used for bonus component calculations */
    @Column(name = "base_salary_gross")
    var baseSalaryGross: Long?,

    /** Gross hourly rate in cents – for UZ HOURLY contracts */
    @Column(name = "hourly_rate_gross")
    var hourlyRateGross: Long?,

    /** Net hourly rate in cents – for B2B contracts */
    @Column(name = "hourly_rate_net")
    var hourlyRateNet: Long?,

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "compensation_config_id")
    var components: MutableList<CompensationComponentEntity> = mutableListOf(),

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): CompensationConfig = CompensationConfig(
        id = CompensationConfigId(id),
        studioId = StudioId(studioId),
        employeeId = EmployeeId(employeeId),
        contractId = EmploymentContractId(contractId),
        effectiveFrom = effectiveFrom,
        effectiveTo = effectiveTo,
        employmentMode = employmentMode,
        etatFraction = etatFraction,
        monthlySalaryGross = monthlySalaryGross?.let { Money.fromCents(it) },
        baseSalaryGross = baseSalaryGross?.let { Money.fromCents(it) },
        hourlyRateGross = hourlyRateGross?.let { Money.fromCents(it) },
        hourlyRateNet = hourlyRateNet?.let { Money.fromCents(it) },
        components = components.map { it.toDomain() },
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(config: CompensationConfig): CompensationConfigEntity {
            val entity = CompensationConfigEntity(
                id = config.id.value,
                studioId = config.studioId.value,
                employeeId = config.employeeId.value,
                contractId = config.contractId.value,
                effectiveFrom = config.effectiveFrom,
                effectiveTo = config.effectiveTo,
                employmentMode = config.employmentMode,
                etatFraction = config.etatFraction,
                monthlySalaryGross = config.monthlySalaryGross?.amountInCents,
                baseSalaryGross = config.baseSalaryGross?.amountInCents,
                hourlyRateGross = config.hourlyRateGross?.amountInCents,
                hourlyRateNet = config.hourlyRateNet?.amountInCents,
                createdAt = config.createdAt,
                updatedAt = config.updatedAt
            )
            entity.components = config.components.map {
                CompensationComponentEntity.fromDomain(it, config.id.value, config.studioId.value)
            }.toMutableList()
            return entity
        }
    }
}
