package pl.detailing.crm.employee.domain

import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.LocalDate

data class ContractAmendment(
    val id: ContractAmendmentId,
    val studioId: StudioId,
    val employeeId: EmployeeId,
    val contractId: EmploymentContractId,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val employmentMode: EmploymentMode,
    val etatFraction: EtatFraction?,
    /** Monthly gross salary – for UOP / UZ SALARY amendments */
    val monthlySalaryGross: Money?,
    /** Gross hourly rate – for UZ HOURLY amendments */
    val hourlyRateGross: Money?,
    /** Net hourly rate – for B2B amendments */
    val hourlyRateNet: Money?,
    val createdAt: Instant
)
