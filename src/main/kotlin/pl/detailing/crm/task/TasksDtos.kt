package pl.detailing.crm.task

import pl.detailing.crm.task.domain.Task
import java.time.Instant

data class TaskDto(
    val id: String,
    val title: String,
    val meta: String?,
    val done: Boolean,
    val createdAt: Instant,
    val completedAt: Instant?
)

data class ArchivedTaskDto(
    val id: String,
    val title: String,
    val meta: String?,
    val done: Boolean,
    val createdAt: Instant,
    val completedAt: Instant?,
    val deletedAt: Instant,
    val deletedByUserName: String?
)

data class TaskPagination(
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
)

data class ArchivedTasksPage(
    val items: List<ArchivedTaskDto>,
    val pagination: TaskPagination
)

fun Task.toDto(): TaskDto = TaskDto(
    id = this.id.toString(),
    title = this.title,
    meta = this.meta,
    done = this.done,
    createdAt = this.createdAt,
    completedAt = this.completedAt
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
