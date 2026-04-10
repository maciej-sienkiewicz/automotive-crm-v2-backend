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
    val baseSalaryGross: Money?,
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
