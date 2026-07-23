package pl.detailing.crm.task.list

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class ListTasksQuery(
    val studioId: StudioId,
    val userId: UserId,
    val isOwner: Boolean
)
