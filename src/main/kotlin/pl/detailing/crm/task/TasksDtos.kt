package pl.detailing.crm.task

import pl.detailing.crm.task.domain.Task
import pl.detailing.crm.task.domain.TaskVisibilityType
import java.time.Instant
import java.util.UUID

data class TaskDto(
    val id: String,
    val title: String,
    val meta: String?,
    val done: Boolean,
    val createdAt: Instant,
    val createdByUserName: String?,
    val completedAt: Instant?,
    val completedByUserName: String?,
    val visibilityType: String = "ALL",
    val visibleToUserIds: List<String> = emptyList(),
    val visibleToUserNames: List<String> = emptyList(),
    val visibleToRoleId: String? = null,
    val visibleToRoleName: String? = null
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
    completedByUserName: String? = null,
    visibleToUserNames: List<String> = emptyList(),
    visibleToRoleName: String? = null
): TaskDto = TaskDto(
    id = id.value.toString(),
    title = title,
    meta = meta,
    done = done,
    createdAt = createdAt,
    createdByUserName = createdByUserName,
    completedAt = completedAt,
    completedByUserName = completedByUserName,
    visibilityType = visibilityType.name,
    visibleToUserIds = visibleToUserIds.map { it.toString() },
    visibleToUserNames = visibleToUserNames,
    visibleToRoleId = visibleToRoleId?.toString(),
    visibleToRoleName = visibleToRoleName
)

data class CreateTaskRequest(
    val title: String,
    val meta: String?,
    val visibilityType: String = "ALL",
    val visibleToUserIds: List<String>? = null,
    val visibleToRoleId: String? = null
)

data class UpdateTaskRequest(
    val title: String?,
    val meta: String?,
    val done: Boolean?
)

// ─── Visibility options ───────────────────────────────────────────────────────

data class TaskVisibilityUser(
    val userId: String,
    val fullName: String
)

data class TaskVisibilityRole(
    val roleId: String,
    val name: String
)

data class TaskVisibilityOptionsResponse(
    val users: List<TaskVisibilityUser>,
    val roles: List<TaskVisibilityRole>
)
