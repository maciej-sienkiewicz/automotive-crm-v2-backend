package pl.detailing.crm.task.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Per-user read receipt for a task — powers the unread badge of the
 * "Powiadomienia" (my tasks) surface. A row means "this user has seen this
 * task"; absence means unread. Rows are only ever inserted (marking as read),
 * never updated or deleted — a deleted task simply leaves orphaned receipts
 * that no query joins to.
 */
@Entity
@Table(
    name = "task_reads",
    indexes = [Index(name = "idx_task_reads_user", columnList = "user_id")]
)
@IdClass(TaskReadId::class)
class TaskReadEntity(
    @Id
    @Column(name = "task_id", columnDefinition = "uuid")
    var taskId: UUID,

    @Id
    @Column(name = "user_id", columnDefinition = "uuid")
    var userId: UUID,

    @Column(name = "read_at", nullable = false, columnDefinition = "timestamp with time zone")
    var readAt: Instant = Instant.now()
)

data class TaskReadId(
    var taskId: UUID = UUID(0, 0),
    var userId: UUID = UUID(0, 0)
) : Serializable

interface TaskReadRepository : JpaRepository<TaskReadEntity, TaskReadId> {
    fun findByUserIdAndTaskIdIn(userId: UUID, taskIds: Collection<UUID>): List<TaskReadEntity>
}
