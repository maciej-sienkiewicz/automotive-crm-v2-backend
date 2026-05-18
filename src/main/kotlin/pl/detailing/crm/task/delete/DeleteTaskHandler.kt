package pl.detailing.crm.task.delete

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.task.infrastructure.TaskRepository
import java.time.Instant

@Service
class DeleteTaskHandler(
    private val taskRepository: TaskRepository
) {
    private val log = LoggerFactory.getLogger(DeleteTaskHandler::class.java)

    @Transactional
    suspend fun handle(command: DeleteTaskCommand): Unit =
        withContext(Dispatchers.IO) {
            val entity = taskRepository.findById(command.taskId.value)
                .orElseThrow { EntityNotFoundException("Zadanie nie zostało znalezione: ${command.taskId}") }

            if (entity.studioId != command.studioId.value) {
                throw ForbiddenException("Zadanie nie należy do tego studia")
            }

            if (entity.deletedAt != null) {
                throw EntityNotFoundException("Zadanie nie zostało znalezione: ${command.taskId}")
            }

            entity.deletedAt = Instant.now()
            entity.deletedByUserId = command.userId.value
            entity.updatedAt = Instant.now()
            taskRepository.save(entity)

            log.info("[TASKS] Archived task: taskId={}, studioId={}, deletedBy={}", command.taskId.value, command.studioId.value, command.userId.value)
        }
}
