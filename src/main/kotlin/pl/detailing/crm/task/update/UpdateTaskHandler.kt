package pl.detailing.crm.task.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.task.domain.Task
import pl.detailing.crm.task.infrastructure.TaskRepository
import java.time.Instant

@Service
class UpdateTaskHandler(
    private val taskRepository: TaskRepository,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(UpdateTaskHandler::class.java)

    @Transactional
    suspend fun handle(command: UpdateTaskCommand): Task =
        withContext(Dispatchers.IO) {
            val entity = taskRepository.findById(command.taskId.value)
                .orElseThrow { EntityNotFoundException("Task not found: ${command.taskId}") }

            if (entity.studioId != command.studioId.value) {
                throw ForbiddenException("Task does not belong to this studio")
            }

            if (command.title != null && command.title.isBlank()) {
                throw ValidationException("Task title cannot be blank")
            }

            val oldValues = mapOf(
                "title" to entity.title,
                "meta" to entity.meta,
                "done" to entity.done.toString()
            )

            command.title?.let { entity.title = it.trim() }
            command.meta?.let { entity.meta = it.trim().ifBlank { null } }
            command.done?.let { entity.done = it }
            entity.updatedAt = Instant.now()

            taskRepository.save(entity)

            log.info("[TASKS] Updated task: taskId={}, studioId={}", entity.id, entity.studioId)

            val newValues = mapOf(
                "title" to entity.title,
                "meta" to entity.meta,
                "done" to entity.done.toString()
            )
            val changes = auditService.computeChanges(oldValues, newValues)

            if (changes.isNotEmpty()) {
                auditService.log(LogAuditCommand(
                    studioId = command.studioId,
                    userId = command.userId,
                    userDisplayName = command.userName ?: "",
                    module = AuditModule.TASK,
                    entityId = command.taskId.value.toString(),
                    entityDisplayName = entity.title,
                    action = AuditAction.UPDATE,
                    changes = changes
                ))
            }

            entity.toDomain()
        }
}
