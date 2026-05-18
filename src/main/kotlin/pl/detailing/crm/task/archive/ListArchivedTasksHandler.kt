package pl.detailing.crm.task.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.task.domain.Task
import pl.detailing.crm.task.infrastructure.TaskRepository

@Service
class ListArchivedTasksHandler(
    private val taskRepository: TaskRepository
) {
    private val log = LoggerFactory.getLogger(ListArchivedTasksHandler::class.java)

    @Transactional(readOnly = true)
    suspend fun handle(query: ListArchivedTasksQuery): List<Task> =
        withContext(Dispatchers.IO) {
            val tasks = taskRepository.findByStudioIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(query.studioId.value)
                .map { it.toDomain() }

            log.debug("[TASKS] Listed archived tasks: studioId={}, count={}", query.studioId.value, tasks.size)

            tasks
        }
}
