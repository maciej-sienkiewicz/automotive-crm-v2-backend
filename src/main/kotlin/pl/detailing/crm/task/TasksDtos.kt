package pl.detailing.crm.task

import pl.detailing.crm.task.domain.Task
import java.time.Instant

data class TaskDto(
    val id: String,
    val title: String,
    val meta: String?,
    val done: Boolean,
    val createdAt: Instant
)

fun Task.toDto(): TaskDto = TaskDto(
    id = this.id.toString(),
    title = this.title,
    meta = this.meta,
    done = this.done,
    createdAt = this.createdAt
)

data class CreateTaskRequest(
    val title: String,
    val meta: String?
)

data class UpdateTaskRequest(
    val title: String?,
    val meta: String?,
    val done: Boolean?
)
