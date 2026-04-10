package pl.detailing.crm.employee.contract

import pl.detailing.crm.shared.*
import java.math.BigDecimal
import java.time.LocalDate

data class CreateContractCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val employeeId: EmployeeId,
    val contractType: ContractType,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val workingHoursPerWeek: BigDecimal,
    val trialPeriodEndDate: LocalDate?,
    val documentFileId: String?
)
