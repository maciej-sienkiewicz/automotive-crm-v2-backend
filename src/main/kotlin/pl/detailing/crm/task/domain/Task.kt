package pl.detailing.crm.task.domain

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.TaskId
import pl.detailing.crm.shared.UserId
import java.time.Instant

data class Task(
    val id: TaskId,
    val studioId: StudioId,
    val createdByUserId: UserId,
    val title: String,
    val meta: String?,
    val done: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
    val deletedAt: Instant?,
    val deletedByUserId: UserId?
)
