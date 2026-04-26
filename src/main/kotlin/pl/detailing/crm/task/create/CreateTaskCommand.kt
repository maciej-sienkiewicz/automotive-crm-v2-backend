package pl.detailing.crm.task.create

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class CreateTaskCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val title: String,
    val meta: String?
)
