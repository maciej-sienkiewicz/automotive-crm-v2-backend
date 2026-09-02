package pl.detailing.crm.task.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.TaskId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.task.domain.Task
import pl.detailing.crm.task.infrastructure.TaskEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.task.infrastructure.TaskRepository
import java.time.Instant

@Service
class CreateTaskHandler(
    private val taskRepository: TaskRepository,
    private val auditService: AuditService,
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository
) {
    private val log = LoggerFactory.getLogger(CreateTaskHandler::class.java)

    @Transactional
    suspend fun handle(command: CreateTaskCommand): Task =
        withContext(Dispatchers.IO) {
            if (command.title.isBlank()) {
                throw ValidationException("Tytuł zadania nie może być pusty")
            }

            // Visibility targets are client-supplied ids: each must be a user / role of THIS
            // studio, otherwise the task list would later render a foreign user's or role's name.
            command.visibleToUserIds.forEach { uid ->
                userRepository.findByIdAndStudioId(uid, command.studioId.value)
                    ?: throw EntityNotFoundException("Użytkownik nie istnieje: $uid")
            }
            command.visibleToRoleId?.let { rid ->
                roleRepository.findByIdAndStudioId(rid, command.studioId.value)
                    ?: throw EntityNotFoundException("Rola nie istnieje: $rid")
            }

            val task = Task(
                id = TaskId.random(),
                studioId = command.studioId,
                createdByUserId = command.userId,
                title = command.title.trim(),
                meta = command.meta?.trim()?.ifBlank { null },
                done = false,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                completedAt = null,
                completedByUserId = null,
                deletedAt = null,
                deletedByUserId = null,
                visibilityType = command.visibilityType,
                visibleToUserIds = command.visibleToUserIds,
                visibleToRoleId = command.visibleToRoleId
            )

            taskRepository.save(TaskEntity.fromDomain(task))

            log.info("[TASKS] Created task: taskId={}, studioId={}", task.id.value, task.studioId.value)

            auditService.log(LogAuditCommand(
                studioId = command.studioId,
                userId = command.userId,
                userDisplayName = command.userName ?: "",
                module = AuditModule.TASK,
                entityId = task.id.value.toString(),
                entityDisplayName = task.title,
                action = AuditAction.TASK_CREATED,
                changes = listOfNotNull(
                    FieldChange("title", null, task.title),
                    task.meta?.let { FieldChange("context", null, it) }
                )
            ))

            task
        }
}
