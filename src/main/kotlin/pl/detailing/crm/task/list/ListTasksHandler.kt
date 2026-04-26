package pl.detailing.crm.task.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.task.domain.Task
import pl.detailing.crm.task.infrastructure.TaskRepository

@Service
class ListTasksHandler(
    private val taskRepository: TaskRepository
) {
    private val log = LoggerFactory.getLogger(ListTasksHandler::class.java)

    @Transactional(readOnly = true)
    suspend fun handle(query: ListTasksQuery): List<Task> =
        withContext(Dispatchers.IO) {
            val tasks = taskRepository.findByStudioIdOrderByCreatedAtDesc(query.studioId.value)
                .map { it.toDomain() }

            log.debug("[TASKS] Listed tasks: studioId={}, count={}", query.studioId.value, tasks.size)

            tasks
        }
}
