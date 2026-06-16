package pl.detailing.crm.role.permission

import org.springframework.stereotype.Service
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.UserRole
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.user.infrastructure.UserRepository

/**
 * Single source of truth for "does user X have permission Y in studio Z?"
 *
 * Decision logic:
 * 1. OWNER always returns true (full access, no custom role needed).
 * 2. User must have a custom role assigned.
 * 3. That role must include the requested permission.
 * 4. If the permission's module maps to a [FeatureKey], the studio must have
 *    that feature enabled in its active entitlements.
 */
@Service
class PermissionCheckService(
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val entitlementService: EntitlementService
) {
    fun hasPermission(userId: UserId, studioId: StudioId, permission: Permission): Boolean {
        val userEntity = userRepository.findByIdAndStudioId(userId.value, studioId.value)
            ?: return false

        if (userEntity.role == UserRole.OWNER) return true

        val customRoleId = userEntity.customRoleId ?: return false

        val roleEntity = roleRepository.findByIdAndStudioId(customRoleId, studioId.value)
            ?: return false

        if (!roleEntity.permissions.contains(permission)) return false

        val requiredFeature = permission.module.featureKey ?: return true
        return entitlementService.hasFeature(studioId, requiredFeature)
    }
}
