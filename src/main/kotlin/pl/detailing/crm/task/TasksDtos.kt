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
    val deletedByUserId: String?
)

fun Task.toDto(): TaskDto = TaskDto(
    id = this.id.toString(),
    title = this.title,
    meta = this.meta,
    done = this.done,
    createdAt = this.createdAt,
    completedAt = this.completedAt
)

fun Task.toArchivedDto(): ArchivedTaskDto = ArchivedTaskDto(
    id = this.id.toString(),
    title = this.title,
    meta = this.meta,
    done = this.done,
    createdAt = this.createdAt,
    completedAt = this.completedAt,
    deletedAt = this.deletedAt!!,
    deletedByUserId = this.deletedByUserId?.toString()
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
