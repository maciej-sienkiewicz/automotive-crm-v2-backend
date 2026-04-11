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
    /** Required when employmentMode = SALARY */
    val etatFraction: EtatFraction?,
    /** Monthly gross salary entered by admin – required when employmentMode = SALARY */
    val monthlySalaryGross: Money?,
    /** Hourly gross rate entered by admin – required when employmentMode = HOURLY */
    val hourlyRateGross: Money?,
    val components: List<CompensationComponent>
)
