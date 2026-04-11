package pl.detailing.crm.employee.domain

import pl.detailing.crm.shared.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class CompensationConfig(
    val id: CompensationConfigId,
    val studioId: StudioId,
    val employeeId: EmployeeId,
    val contractId: EmploymentContractId,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val employmentMode: EmploymentMode,
    val etatFraction: EtatFraction?,
    /** Monthly gross salary – populated for UOP / UZ SALARY contracts */
    val monthlySalaryGross: Money?,
    /** Additional base salary figure used for component calculations */
    val baseSalaryGross: Money?,
    /** Gross hourly rate – populated for UZ HOURLY contracts */
    val hourlyRateGross: Money?,
    /** Net hourly rate – populated for B2B contracts (invoice amount) */
    val hourlyRateNet: Money?,
    val components: List<CompensationComponent>,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class CompensationComponent(
    val id: CompensationComponentId,
    val name: String,
    val type: ComponentType,
    val calculationBase: CalculationBase?,
    val value: BigDecimal,
    val thresholds: List<Threshold>,
    val frequency: PaymentFrequency,
    val isActive: Boolean,
    val description: String?
)

data class Threshold(
    val minValue: Money,
    val maxValue: Money?,
    val rate: BigDecimal
)
