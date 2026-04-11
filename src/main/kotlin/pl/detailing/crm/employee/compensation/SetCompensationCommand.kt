package pl.detailing.crm.employee.compensation

import pl.detailing.crm.employee.domain.CompensationComponent
import pl.detailing.crm.shared.*
import java.time.LocalDate

data class SetCompensationCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val employeeId: EmployeeId,
    val contractId: EmploymentContractId,
    val effectiveFrom: LocalDate,
    val employmentMode: EmploymentMode,
    val etatFraction: EtatFraction?,
    /** Monthly gross salary – for UOP / UZ SALARY contracts */
    val monthlySalaryGross: Money?,
    /** Additional base used for bonus component calculations */
    val baseSalaryGross: Money?,
    /** Gross hourly rate – for UZ HOURLY contracts */
    val hourlyRateGross: Money?,
    /** Net hourly rate – for B2B contracts */
    val hourlyRateNet: Money?,
    val components: List<CompensationComponent>
)
