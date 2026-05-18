package pl.detailing.crm.task.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.task.ArchivedTaskDto
import pl.detailing.crm.task.infrastructure.TaskRepository
import pl.detailing.crm.user.infrastructure.UserRepository

@Service
class ListArchivedTasksHandler(
    private val taskRepository: TaskRepository,
    private val userRepository: UserRepository
) {
    private val log = LoggerFactory.getLogger(ListArchivedTasksHandler::class.java)

    @Transactional(readOnly = true)
    suspend fun handle(query: ListArchivedTasksQuery): List<ArchivedTaskDto> =
        withContext(Dispatchers.IO) {
            val entities = taskRepository.findByStudioIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(query.studioId.value)

            val userIds = entities.mapNotNull { it.deletedByUserId }.distinct()
            val usersById = if (userIds.isEmpty()) emptyMap()
                            else userRepository.findAllById(userIds).associateBy { it.id }

            val result = entities.map { entity ->
                val user = entity.deletedByUserId?.let { usersById[it] }
                ArchivedTaskDto(
                    id = entity.id.toString(),
                    title = entity.title,
                    meta = entity.meta,
                    done = entity.done,
                    createdAt = entity.createdAt,
                    completedAt = entity.completedAt,
                    deletedAt = entity.deletedAt!!,
                    deletedByUserName = user?.let { "${it.firstName} ${it.lastName}" }
                )
            }

            log.debug("[TASKS] Listed archived tasks: studioId={}, count={}", query.studioId.value, result.size)

            result
        }
}
