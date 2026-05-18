package pl.detailing.crm.task.delete

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.TaskId
import pl.detailing.crm.shared.UserId

data class DeleteTaskCommand(
    val taskId: TaskId,
    val studioId: StudioId,
    val userId: UserId
)
