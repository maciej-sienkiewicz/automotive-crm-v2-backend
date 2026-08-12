package pl.detailing.crm.task.my

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.TaskId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.task.domain.TaskVisibility
import pl.detailing.crm.task.infrastructure.TaskEntity
import pl.detailing.crm.task.infrastructure.TaskReadEntity
import pl.detailing.crm.task.infrastructure.TaskReadRepository
import pl.detailing.crm.task.infrastructure.TaskRepository
import pl.detailing.crm.task.update.UpdateTaskCommand
import pl.detailing.crm.task.update.UpdateTaskHandler
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Instant
import java.util.UUID

data class MyTaskDto(
    val id: String,
    val title: String,
    val meta: String?,
    val done: Boolean,
    val createdAt: Instant,
    val createdByUserName: String?,
    val completedAt: Instant?,
    /** True until the user opens the notifications view (no task_reads row yet). */
    val unread: Boolean
)

data class MyTasksSummaryDto(
    /** Visible, not completed. */
    val pendingCount: Int,
    /** Visible, not completed, never seen by this user. */
    val unreadCount: Int
)

/**
 * Self-service view of the studio task board: only tasks visible to the
 * authenticated user (same [TaskVisibility] rules as the TASKS_VIEW listing),
 * decorated with a per-user unread flag. Powers the "Powiadomienia" tab for
 * roles that cannot see the dashboard.
 */
@Service
class MyTasksService(
    private val taskRepository: TaskRepository,
    private val taskReadRepository: TaskReadRepository,
    private val userRepository: UserRepository,
    private val updateTaskHandler: UpdateTaskHandler
) {
    @Transactional(readOnly = true)
    suspend fun list(studioId: StudioId, userId: UserId, isOwner: Boolean): List<MyTaskDto> =
        withContext(Dispatchers.IO) {
            val visible = visibleTasks(studioId, userId, isOwner)
            val readTaskIds = readTaskIds(userId, visible)
            val creatorsById = userRepository.findAllById(visible.map { it.createdByUserId }.distinct())
                .associateBy { it.id }

            visible.map { entity ->
                MyTaskDto(
                    id = entity.id.toString(),
                    title = entity.title,
                    meta = entity.meta,
                    done = entity.done,
                    createdAt = entity.createdAt,
                    createdByUserName = creatorsById[entity.createdByUserId]
                        ?.let { "${it.firstName} ${it.lastName}" },
                    completedAt = entity.completedAt,
                    unread = entity.id !in readTaskIds
                )
            }
        }

    @Transactional(readOnly = true)
    suspend fun summary(studioId: StudioId, userId: UserId, isOwner: Boolean): MyTasksSummaryDto =
        withContext(Dispatchers.IO) {
            val visible = visibleTasks(studioId, userId, isOwner)
            val pending = visible.filter { !it.done }
            val readTaskIds = readTaskIds(userId, pending)
            MyTasksSummaryDto(
                pendingCount = pending.size,
                unreadCount = pending.count { it.id !in readTaskIds }
            )
        }

    /** Marks every currently visible task as read; idempotent. */
    @Transactional
    suspend fun markAllRead(studioId: StudioId, userId: UserId, isOwner: Boolean): MyTasksSummaryDto =
        withContext(Dispatchers.IO) {
            val visible = visibleTasks(studioId, userId, isOwner)
            val alreadyRead = readTaskIds(userId, visible)
            val newReads = visible
                .filter { it.id !in alreadyRead }
                .map { TaskReadEntity(taskId = it.id, userId = userId.value, readAt = Instant.now()) }
            if (newReads.isNotEmpty()) taskReadRepository.saveAll(newReads)

            val pending = visible.filter { !it.done }
            MyTasksSummaryDto(pendingCount = pending.size, unreadCount = 0)
        }

    /**
     * Completes/uncompletes a task from the notifications view. Unlike the
     * TASKS_VIEW-gated PATCH, this path verifies the task is actually visible
     * to the caller before delegating to [UpdateTaskHandler].
     */
    suspend fun setDone(studioId: StudioId, userId: UserId, userName: String, isOwner: Boolean, taskId: TaskId, done: Boolean) {
        val visible = withContext(Dispatchers.IO) { visibleTasks(studioId, userId, isOwner) }
        if (visible.none { it.id == taskId.value }) {
            throw EntityNotFoundException("Zadanie nie istnieje")
        }
        updateTaskHandler.handle(
            UpdateTaskCommand(
                taskId = taskId,
                studioId = studioId,
                userId = userId,
                userName = userName,
                title = null,
                meta = null,
                done = done
            )
        )
    }

    private fun visibleTasks(studioId: StudioId, userId: UserId, isOwner: Boolean): List<TaskEntity> {
        val entities = taskRepository.findByStudioIdAndDeletedAtIsNullOrderByCreatedAtDesc(studioId.value)
        val roleId: UUID? = if (isOwner) null
            else userRepository.findById(userId.value).orElse(null)?.customRoleId
        return entities.filter { TaskVisibility.isVisible(it, userId.value, roleId, isOwner) }
    }

    private fun readTaskIds(userId: UserId, tasks: List<TaskEntity>): Set<UUID> =
        if (tasks.isEmpty()) emptySet()
        else taskReadRepository.findByUserIdAndTaskIdIn(userId.value, tasks.map { it.id })
            .mapTo(mutableSetOf()) { it.taskId }
}
