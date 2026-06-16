package pl.detailing.crm.role.update

import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.shared.RoleId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class UpdateRoleCommand(
    val studioId: StudioId,
    val requestedBy: UserId,
    val requestedByName: String?,
    val roleId: RoleId,
    val name: String,
    val description: String?,
    val permissions: Set<Permission>
)
