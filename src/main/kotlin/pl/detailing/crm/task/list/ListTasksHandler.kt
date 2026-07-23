package pl.detailing.crm.task.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.task.TaskDto
import pl.detailing.crm.task.infrastructure.TaskEntity
import pl.detailing.crm.task.infrastructure.TaskRepository
import pl.detailing.crm.user.infrastructure.UserRepository
import java.util.UUID

@Service
class ListTasksHandler(
    private val taskRepository: TaskRepository,
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository
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

            // Collect all user IDs needed: creators, completers, and visibility assignees
            val visibilityUserIds = visibleEntities
                .filter { it.visibilityType == "USERS" }
                .flatMap { it.visibleToUserIds?.split(",")?.filter(String::isNotBlank)?.mapNotNull { id ->
                    runCatching { UUID.fromString(id.trim()) }.getOrNull()
                } ?: emptyList() }
                .distinct()

            val allUserIds = (visibleEntities.map { it.createdByUserId } +
                              visibleEntities.mapNotNull { it.completedByUserId } +
                              visibilityUserIds).distinct()
            val usersById = if (allUserIds.isEmpty()) emptyMap()
                            else userRepository.findAllById(allUserIds).associateBy { it.id }

            // Collect role IDs for ROLE-visibility tasks
            val visibilityRoleIds = visibleEntities
                .filter { it.visibilityType == "ROLE" }
                .mapNotNull { it.visibleToRoleId }
                .distinct()
            val rolesById = if (visibilityRoleIds.isEmpty()) emptyMap()
                            else roleRepository.findAllById(visibilityRoleIds).associateBy { it.id }

            val result = visibleEntities.map { entity ->
                val createdBy = usersById[entity.createdByUserId]
                val completedBy = entity.completedByUserId?.let { usersById[it] }
                val rawUserIds = entity.visibleToUserIds?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                val visibleUserNames = rawUserIds.mapNotNull { idStr ->
                    runCatching { UUID.fromString(idStr.trim()) }.getOrNull()?.let { uid -> usersById[uid] }
                }.map { "${it.firstName} ${it.lastName}" }
                val visibleRoleName = entity.visibleToRoleId?.let { rolesById[it]?.name }
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
                    visibleToUserIds = rawUserIds,
                    visibleToUserNames = visibleUserNames,
                    visibleToRoleId = entity.visibleToRoleId?.toString(),
                    visibleToRoleName = visibleRoleName
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
