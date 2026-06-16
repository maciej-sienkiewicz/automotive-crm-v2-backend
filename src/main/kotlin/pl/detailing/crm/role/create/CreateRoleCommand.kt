package pl.detailing.crm.role.create

import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class CreateRoleCommand(
    val studioId: StudioId,
    val requestedBy: UserId,
    val requestedByName: String?,
    val name: String,
    val description: String?,
    val permissions: Set<Permission>
)
