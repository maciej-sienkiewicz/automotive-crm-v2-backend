package pl.detailing.crm.leads.create

import org.springframework.stereotype.Component
import pl.detailing.crm.user.infrastructure.UserRepository
import java.util.UUID

/**
 * Resolves the sole active user for a studio to use as the default lead assignee.
 * Returns null when there are 0 or 2+ active users (no auto-assignment in those cases).
 */
@Component
class SoleUserResolver(private val userRepository: UserRepository) {

    data class UserInfo(val id: UUID, val name: String)

    fun resolveForStudio(studioId: UUID): UserInfo? {
        val activeUsers = userRepository.findActiveByStudioId(studioId)
        if (activeUsers.size != 1) return null
        val u = activeUsers.first()
        return UserInfo(
            id = u.id,
            name = "${u.firstName} ${u.lastName}".trim()
        )
    }
}
