package pl.detailing.crm.task.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.TaskId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.task.domain.Task
import pl.detailing.crm.task.domain.TaskVisibilityType
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "tasks",
    indexes = [
        Index(name = "idx_tasks_studio_created", columnList = "studio_id, created_at"),
        Index(name = "idx_tasks_studio_done", columnList = "studio_id, done"),
        Index(name = "idx_tasks_studio_deleted", columnList = "studio_id, deleted_at")
    ]
)
class TaskEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "created_by_user_id", nullable = false, columnDefinition = "uuid")
    val createdByUserId: UUID,

    @Column(name = "title", nullable = false, columnDefinition = "text")
    var title: String,

    @Column(name = "meta", nullable = true, columnDefinition = "text")
    var meta: String?,

    @Column(name = "done", nullable = false)
    var done: Boolean,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now(),

    @Column(name = "completed_at", nullable = true, columnDefinition = "timestamp with time zone")
    var completedAt: Instant? = null,

    @Column(name = "completed_by_user_id", nullable = true, columnDefinition = "uuid")
    var completedByUserId: UUID? = null,

    @Column(name = "deleted_at", nullable = true, columnDefinition = "timestamp with time zone")
    var deletedAt: Instant? = null,

    @Column(name = "deleted_by_user_id", nullable = true, columnDefinition = "uuid")
    var deletedByUserId: UUID? = null,

    @Column(name = "visibility_type", nullable = false, length = 10)
    var visibilityType: String = "ALL",

    @Column(name = "visible_to_user_ids", nullable = true, columnDefinition = "text")
    var visibleToUserIds: String? = null,

    @Column(name = "visible_to_role_id", nullable = true, columnDefinition = "uuid")
    var visibleToRoleId: UUID? = null
) {
    fun toDomain(): Task = Task(
        id = TaskId(id),
        studioId = StudioId(studioId),
        createdByUserId = UserId(createdByUserId),
        title = title,
        meta = meta,
        done = done,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
        completedByUserId = completedByUserId?.let { UserId(it) },
        deletedAt = deletedAt,
        deletedByUserId = deletedByUserId?.let { UserId(it) },
        visibilityType = runCatching { TaskVisibilityType.valueOf(visibilityType) }.getOrDefault(TaskVisibilityType.ALL),
        visibleToUserIds = visibleToUserIds?.split(",")?.filter { it.isNotBlank() }?.map { UUID.fromString(it.trim()) } ?: emptyList(),
        visibleToRoleId = visibleToRoleId
    )

    companion object {
        fun fromDomain(task: Task): TaskEntity = TaskEntity(
            id = task.id.value,
            studioId = task.studioId.value,
            createdByUserId = task.createdByUserId.value,
            title = task.title,
            meta = task.meta,
            done = task.done,
            createdAt = task.createdAt,
            updatedAt = task.updatedAt,
            completedAt = task.completedAt,
            completedByUserId = task.completedByUserId?.value,
            deletedAt = task.deletedAt,
            deletedByUserId = task.deletedByUserId?.value,
            visibilityType = task.visibilityType.name,
            visibleToUserIds = task.visibleToUserIds.takeIf { it.isNotEmpty() }?.joinToString(","),
            visibleToRoleId = task.visibleToRoleId
        )
    }
}
