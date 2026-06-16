package pl.detailing.crm.role.domain

import pl.detailing.crm.shared.RoleId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant

data class Role(
    val id: RoleId,
    val studioId: StudioId,
    val name: String,
    val description: String?,
    val permissions: Set<Permission>,
    val createdBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
)
