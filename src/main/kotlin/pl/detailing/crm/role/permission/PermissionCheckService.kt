package pl.detailing.crm.role.permission

import org.springframework.stereotype.Service
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.domain.PermissionHierarchy
import pl.detailing.crm.role.domain.PermissionModule
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.user.infrastructure.UserRepository

/**
 * Single source of truth for "does user X have permission Y in studio Z?"
 *
 * Decision logic:
 * 1. Studio owner always returns true (full access, no custom role needed).
 * 2. User must have a custom role assigned.
 * 3. That role must include the requested permission (after tree close + cross-module expansion).
 * 4. If the permission maps to a feature key ([Permission.effectiveFeatureKey]),
 *    the studio must have that feature enabled in its active entitlements.
 *
 * Cross-module expansion (see [expandCrossModule]):
 * - Any Finance or Statistics permission → [Permission.SERVICES_VIEW] implied.
 * - [Permission.VISITS_SERVICES_VIEW] or any descendant → [Permission.SERVICES_VIEW] implied.
 *   (service catalog access is needed when managing services within a visit).
 */
@Service
class PermissionCheckService(
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val entitlementService: EntitlementService
) {
    /**
     * Checks whether the user has [permission] in the given studio.
     * Delegates to [getPermissions] so the cross-module expansion logic is applied once.
     */
    fun hasPermission(userId: UserId, studioId: StudioId, permission: Permission): Boolean =
        getPermissions(userId, studioId)?.contains(permission) ?: true // null = owner

    /**
     * Returns the effective permissions for a user, filtered through feature entitlements
     * and expanded with cross-module implications.
     * Returns null for studio owners (they have unrestricted access to everything).
     * Returns an empty set if the user has no custom role.
     */
    fun getPermissions(userId: UserId, studioId: StudioId): Set<Permission>? {
        val userEntity = userRepository.findByIdAndStudioId(userId.value, studioId.value)
            ?: return emptySet()

        if (userEntity.isOwner) return null

        val customRoleId = userEntity.customRoleId ?: return emptySet()

        val roleEntity = roleRepository.findByIdAndStudioId(customRoleId, studioId.value)
            ?: return emptySet()

        // toDomain closes over the tree (adds ancestors + VISITS_CREATE module-grant).
        val base = roleEntity.toDomain().permissions.filterTo(mutableSetOf()) { permission ->
            val requiredFeature = permission.effectiveFeatureKey ?: return@filterTo true
            entitlementService.hasFeature(studioId, requiredFeature)
        }

        return expandCrossModule(base)
    }

    /**
     * Applies cross-module access rules that cannot be expressed in the single-module tree:
     * - Finance or Statistics permissions → [Permission.SERVICES_VIEW]
     * - [Permission.VISITS_SERVICES_VIEW] or any descendant → [Permission.SERVICES_VIEW]
     *   (visit coordinators need the service catalog when building a visit).
     */
    private fun expandCrossModule(permissions: Set<Permission>): Set<Permission> {
        val servicesViewGranted =
            permissions.any { it.module == PermissionModule.FINANCE } ||
            permissions.any { it.module == PermissionModule.STATISTICS } ||
            PermissionHierarchy.subtreeOf(Permission.VISITS_SERVICES_VIEW).any { it in permissions }

        if (!servicesViewGranted) return permissions

        return (permissions + Permission.SERVICES_VIEW)
    }
}
