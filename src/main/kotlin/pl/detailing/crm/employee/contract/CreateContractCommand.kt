package pl.detailing.crm.employee.contract

import pl.detailing.crm.shared.*
import java.time.LocalDate

data class CreateContractCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val employeeId: EmployeeId,
    val contractType: ContractType,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val documentFileId: String?,
    /** Initial compensation – mandatory at contract creation */
    val initialCompensation: InitialCompensationData
)

/**
 * Compensation data carried by contract creation.
 *
 * - SALARY: UOP / UZ – fixed monthly gross salary + etat fraction
 * - HOURLY / GROSS: UZ – gross hourly rate
 * - HOURLY / NET: B2B – net hourly rate (invoice amount)
 */
sealed class InitialCompensationData {
    data class Salary(
        val etatFraction: EtatFraction,
        val monthlySalaryGrossCents: Long
    ) : InitialCompensationData()

    data class HourlyGross(
        val hourlyRateGrossCents: Long
    ) : InitialCompensationData()

    data class HourlyNet(
        val hourlyRateNetCents: Long
    ) : InitialCompensationData()
}
