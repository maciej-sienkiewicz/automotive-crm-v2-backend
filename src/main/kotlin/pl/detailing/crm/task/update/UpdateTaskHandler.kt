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
                .orElseThrow { EntityNotFoundException("Zadanie nie zostało znalezione: ${command.taskId}") }

            if (entity.studioId != command.studioId.value) {
                throw ForbiddenException("Zadanie nie należy do tego studia")
            }

            if (entity.deletedAt != null) {
                throw EntityNotFoundException("Zadanie nie zostało znalezione: ${command.taskId}")
            }

            if (command.title != null && command.title.isBlank()) {
                throw ValidationException("Tytuł zadania nie może być pusty")
            }

            val oldValues = mapOf(
                "title" to entity.title,
                "meta" to entity.meta,
                "done" to entity.done.toString()
            )

            command.title?.let { entity.title = it.trim() }
            command.meta?.let { entity.meta = it.trim().ifBlank { null } }
            command.done?.let { newDone ->
                if (newDone && !entity.done) {
                    entity.completedAt = Instant.now()
                } else if (!newDone && entity.done) {
                    entity.completedAt = null
                }
                entity.done = newDone
            }
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
