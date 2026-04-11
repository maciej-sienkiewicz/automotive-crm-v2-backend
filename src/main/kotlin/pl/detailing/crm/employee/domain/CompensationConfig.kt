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
    /** Mode determines how payroll is calculated */
    val employmentMode: EmploymentMode,
    /**
     * Fraction of full-time etat – set only when [employmentMode] = SALARY.
     * Determines standard monthly hours used to derive [hourlyRateGross].
     */
    val etatFraction: EtatFraction?,
    /**
     * Fixed monthly gross salary – set only when [employmentMode] = SALARY.
     * This is what the admin enters; [hourlyRateGross] is auto-derived from it.
     */
    val monthlySalaryGross: Money?,
    /**
     * Gross hourly rate.
     * - SALARY mode: derived automatically as monthlySalaryGross / etatFraction.standardMonthlyHours
     * - HOURLY mode: entered directly by the admin
     */
    val hourlyRateGross: Money?,
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
