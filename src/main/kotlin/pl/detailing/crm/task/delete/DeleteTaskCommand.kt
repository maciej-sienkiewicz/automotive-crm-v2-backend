package pl.detailing.crm.task.delete

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.TaskId

data class DeleteTaskCommand(
    val taskId: TaskId,
    val studioId: StudioId
)
