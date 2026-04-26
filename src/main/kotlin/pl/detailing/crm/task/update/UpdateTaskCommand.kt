package pl.detailing.crm.task.update

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.TaskId
import pl.detailing.crm.shared.UserId

data class UpdateTaskCommand(
    val taskId: TaskId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val title: String?,
    val meta: String?,
    val done: Boolean?
)
