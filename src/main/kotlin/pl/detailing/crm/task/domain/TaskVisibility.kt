package pl.detailing.crm.task.domain

import pl.detailing.crm.task.infrastructure.TaskEntity
import java.util.UUID

/**
 * Single source of truth for "can this user see this task?" — shared by the
 * TASKS_VIEW listing and the self-service "my tasks / notifications" surface.
 *
 * Rules (in order): owners see everything; creators see their own tasks;
 * otherwise the task's visibility targeting decides (USERS list / ROLE match /
 * ALL default).
 */
object TaskVisibility {

    fun isVisible(entity: TaskEntity, userId: UUID, userRoleId: UUID?, isOwner: Boolean): Boolean {
        if (isOwner) return true
        if (entity.createdByUserId == userId) return true
        return when (entity.visibilityType) {
            "USERS" -> entity.visibleToUserIds?.split(",")?.any { it.trim() == userId.toString() } == true
            "ROLE" -> userRoleId != null && entity.visibleToRoleId == userRoleId
            else -> true // ALL or unknown
        }
    }
}
