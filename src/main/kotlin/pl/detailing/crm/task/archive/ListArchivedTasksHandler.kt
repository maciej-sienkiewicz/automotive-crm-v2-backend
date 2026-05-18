package pl.detailing.crm.task.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.task.ArchivedTaskDto
import pl.detailing.crm.task.ArchivedTasksPage
import pl.detailing.crm.task.TaskPagination
import pl.detailing.crm.task.infrastructure.TaskRepository
import pl.detailing.crm.user.infrastructure.UserRepository

@Service
class ListArchivedTasksHandler(
    private val taskRepository: TaskRepository,
    private val userRepository: UserRepository
) {
    private val log = LoggerFactory.getLogger(ListArchivedTasksHandler::class.java)

    @Transactional(readOnly = true)
    suspend fun handle(query: ListArchivedTasksQuery): ArchivedTasksPage =
        withContext(Dispatchers.IO) {
            val pageable = PageRequest.of(
                query.page - 1,
                query.pageSize,
                Sort.by(Sort.Direction.DESC, "deletedAt")
            )

            val pageResult = taskRepository.findArchivedByStudioId(
                studioId = query.studioId.value,
                search = query.search?.trim()?.takeIf { it.isNotBlank() },
                pageable = pageable
            )

            val userIds = pageResult.content.mapNotNull { it.deletedByUserId }.distinct()
            val usersById = if (userIds.isEmpty()) emptyMap()
                            else userRepository.findAllById(userIds).associateBy { it.id }

            val items = pageResult.content.map { entity ->
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

            log.debug("[TASKS] Listed archived tasks: studioId={}, page={}, count={}", query.studioId.value, query.page, items.size)

            ArchivedTasksPage(
                items = items,
                pagination = TaskPagination(
                    total = pageResult.totalElements.toInt(),
                    page = query.page,
                    pageSize = query.pageSize,
                    totalPages = pageResult.totalPages
                )
            )
        }
}
