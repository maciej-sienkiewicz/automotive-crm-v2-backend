package pl.detailing.crm.task.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.TaskId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.task.domain.Task
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "tasks",
    indexes = [
        Index(name = "idx_tasks_studio_created", columnList = "studio_id, created_at"),
        Index(name = "idx_tasks_studio_done", columnList = "studio_id, done")
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
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): Task = Task(
        id = TaskId(id),
        studioId = StudioId(studioId),
        createdByUserId = UserId(createdByUserId),
        title = title,
        meta = meta,
        done = done,
        createdAt = createdAt,
        updatedAt = updatedAt
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
            updatedAt = task.updatedAt
        )
    }
}
