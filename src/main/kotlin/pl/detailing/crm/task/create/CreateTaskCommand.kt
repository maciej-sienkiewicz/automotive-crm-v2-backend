package pl.detailing.crm.task.create

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.task.domain.TaskVisibilityType
import java.util.UUID

data class CreateTaskCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val title: String,
    val meta: String?,
    val visibilityType: TaskVisibilityType = TaskVisibilityType.ALL,
    val visibleToUserIds: List<UUID> = emptyList(),
    val visibleToRoleId: UUID? = null
)
