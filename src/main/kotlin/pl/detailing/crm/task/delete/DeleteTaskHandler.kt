package pl.detailing.crm.task.delete

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.task.infrastructure.TaskRepository

@Service
class DeleteTaskHandler(
    private val taskRepository: TaskRepository
) {
    private val log = LoggerFactory.getLogger(DeleteTaskHandler::class.java)

    @Transactional
    suspend fun handle(command: DeleteTaskCommand): Unit =
        withContext(Dispatchers.IO) {
            val entity = taskRepository.findById(command.taskId.value)
                .orElseThrow { EntityNotFoundException("Task not found: ${command.taskId}") }

            if (entity.studioId != command.studioId.value) {
                throw ForbiddenException("Task does not belong to this studio")
            }

            taskRepository.delete(entity)

            log.info("[TASKS] Deleted task: taskId={}, studioId={}", command.taskId.value, command.studioId.value)
        }
}
