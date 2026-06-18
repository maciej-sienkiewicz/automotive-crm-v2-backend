package pl.detailing.crm.employee.account

import pl.detailing.crm.shared.EmployeeId
import pl.detailing.crm.shared.RoleId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class ProvisionEmployeeAccountCommand(
    val studioId: StudioId,
    val requestedBy: UserId,
    val requestedByName: String?,
    val employeeId: EmployeeId,
    val email: String,
    val roleId: RoleId? = null
)
