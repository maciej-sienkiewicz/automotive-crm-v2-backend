package pl.detailing.crm.task.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.task.TaskDto
import pl.detailing.crm.task.infrastructure.TaskRepository
import pl.detailing.crm.user.infrastructure.UserRepository

@Service
class ListTasksHandler(
    private val taskRepository: TaskRepository,
    private val userRepository: UserRepository
) {
    private val log = LoggerFactory.getLogger(ListTasksHandler::class.java)

    @Transactional(readOnly = true)
    suspend fun handle(query: ListTasksQuery): List<TaskDto> =
        withContext(Dispatchers.IO) {
            val entities = taskRepository.findByStudioIdAndDeletedAtIsNullOrderByCreatedAtDesc(query.studioId.value)

            val userIds = entities.mapNotNull { it.completedByUserId }.distinct()
            val usersById = if (userIds.isEmpty()) emptyMap()
                            else userRepository.findAllById(userIds).associateBy { it.id }

            val result = entities.map { entity ->
                val completedBy = entity.completedByUserId?.let { usersById[it] }
                TaskDto(
                    id = entity.id.toString(),
                    title = entity.title,
                    meta = entity.meta,
                    done = entity.done,
                    createdAt = entity.createdAt,
                    completedAt = entity.completedAt,
                    completedByUserName = completedBy?.let { "${it.firstName} ${it.lastName}" }
                )
            }

            log.debug("[TASKS] Listed tasks: studioId={}, count={}", query.studioId.value, result.size)

            result
        }
}
