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
import pl.detailing.crm.task.infrastructure.TaskRepository
import java.time.Instant

@Service
class CreateTaskHandler(
    private val taskRepository: TaskRepository,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(CreateTaskHandler::class.java)

    @Transactional
    suspend fun handle(command: CreateTaskCommand): Task =
        withContext(Dispatchers.IO) {
            if (command.title.isBlank()) {
                throw ValidationException("Task title cannot be blank")
            }

            val task = Task(
                id = TaskId.random(),
                studioId = command.studioId,
                createdByUserId = command.userId,
                title = command.title.trim(),
                meta = command.meta?.trim()?.ifBlank { null },
                done = false,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
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
                action = AuditAction.CREATE,
                changes = listOfNotNull(
                    FieldChange("title", null, task.title),
                    task.meta?.let { FieldChange("meta", null, it) }
                )
            ))

            task
        }
}
