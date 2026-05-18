package pl.detailing.crm.task

import pl.detailing.crm.task.domain.Task
import java.time.Instant

data class TaskDto(
    val id: String,
    val title: String,
    val meta: String?,
    val done: Boolean,
    val createdAt: Instant,
    val createdByUserName: String?,
    val completedAt: Instant?,
    val completedByUserName: String?
)

data class ArchivedTaskDto(
    val id: String,
    val title: String,
    val meta: String?,
    val done: Boolean,
    val createdAt: Instant,
    val createdByUserName: String?,
    val completedAt: Instant?,
    val completedByUserName: String?,
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



fun Task.toDto(
    createdByUserName: String? = null,
    completedByUserName: String? = null
): TaskDto = TaskDto(
    id = id.value.toString(),
    title = title,
    meta = meta,
    done = done,
    createdAt = createdAt,
    createdByUserName = createdByUserName,
    completedAt = completedAt,
    completedByUserName = completedByUserName
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
