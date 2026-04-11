package pl.detailing.crm.employee.contract

import pl.detailing.crm.shared.*
import java.time.LocalDate

data class CreateAmendmentCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val employeeId: EmployeeId,
    val contractId: EmploymentContractId,
    val effectiveFrom: LocalDate,
    val compensation: InitialCompensationData
)
