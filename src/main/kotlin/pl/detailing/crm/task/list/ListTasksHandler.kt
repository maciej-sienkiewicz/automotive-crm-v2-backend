package pl.detailing.crm.task.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.task.TaskDto
import pl.detailing.crm.task.infrastructure.TaskEntity
import pl.detailing.crm.task.infrastructure.TaskRepository
import pl.detailing.crm.user.infrastructure.UserRepository
import java.util.UUID

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

            val currentUserRoleId: UUID? = if (query.isOwner) null
                else userRepository.findById(query.userId.value).orElse(null)?.customRoleId

            val visibleEntities = entities.filter { entity ->
                isVisible(entity, query.userId.value, currentUserRoleId, query.isOwner)
            }

            val userIds = (visibleEntities.map { it.createdByUserId } +
                           visibleEntities.mapNotNull { it.completedByUserId }).distinct()
            val usersById = if (userIds.isEmpty()) emptyMap()
                            else userRepository.findAllById(userIds).associateBy { it.id }

            val result = visibleEntities.map { entity ->
                val createdBy = usersById[entity.createdByUserId]
                val completedBy = entity.completedByUserId?.let { usersById[it] }
                val userIds = entity.visibleToUserIds?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                TaskDto(
                    id = entity.id.toString(),
                    title = entity.title,
                    meta = entity.meta,
                    done = entity.done,
                    createdAt = entity.createdAt,
                    createdByUserName = createdBy?.let { "${it.firstName} ${it.lastName}" },
                    completedAt = entity.completedAt,
                    completedByUserName = completedBy?.let { "${it.firstName} ${it.lastName}" },
                    visibilityType = entity.visibilityType,
                    visibleToUserIds = userIds,
                    visibleToRoleId = entity.visibleToRoleId?.toString()
                )
            }

            log.debug("[TASKS] Listed tasks: studioId={}, visible={}/{}", query.studioId.value, result.size, entities.size)

            result
        }

    private fun isVisible(entity: TaskEntity, userId: UUID, userRoleId: UUID?, isOwner: Boolean): Boolean {
        if (isOwner) return true
        if (entity.createdByUserId == userId) return true
        return when (entity.visibilityType) {
            "USERS" -> entity.visibleToUserIds?.split(",")?.any { it.trim() == userId.toString() } == true
            "ROLE" -> userRoleId != null && entity.visibleToRoleId == userRoleId
            else -> true // ALL or unknown
        }
    }
}
