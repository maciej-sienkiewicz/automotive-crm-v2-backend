package pl.detailing.crm.role.permission

import org.springframework.stereotype.Service
import pl.detailing.crm.role.domain.Permission
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
 * 3. That role must include the requested permission. The stored set is already closed
 *    over the whole dependency graph — ancestor chains and catalog implications
 *    ([pl.detailing.crm.role.domain.PermissionHierarchy.close] runs on write and on
 *    read) — so this is a plain lookup.
 * 4. If the permission maps to a feature key ([Permission.effectiveFeatureKey]),
 *    the studio must have that feature enabled in its active entitlements.
 *
 * Role resolution (steps 1–3) goes through [PermissionSnapshotCache] — a short-TTL Redis
 * cache with explicit eviction on role mutations — so per-request checks (including the
 * PII filter, which runs on every request) do not hit the database.
 */
@Service
class PermissionCheckService(
    private val snapshotCache: PermissionSnapshotCache,
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val entitlementService: EntitlementService
) {
    /**
     * Checks whether the user has [permission] in the given studio.
     * Delegates to [getPermissions] so the feature-entitlement filtering is applied once.
     */
    fun hasPermission(userId: UserId, studioId: StudioId, permission: Permission): Boolean =
        getPermissions(userId, studioId)?.contains(permission) ?: true // null = owner

    /**
     * Returns the effective permissions for a user, filtered through feature entitlements.
     * Returns null for studio owners (they have unrestricted access to everything).
     * Returns an empty set if the user has no custom role.
     */
    fun getPermissions(userId: UserId, studioId: StudioId): Set<Permission>? {
        val snapshot = snapshotCache.snapshot(userId.value, studioId.value)
        if (snapshot.owner) return null

        return snapshot.permissionCodes
            .mapNotNull { Permission.fromStoredCode(it) }
            .filterTo(mutableSetOf()) { permission ->
                val requiredFeature = permission.effectiveFeatureKey ?: return@filterTo true
                entitlementService.hasFeature(studioId, requiredFeature)
            }
    }

    /**
     * Returns true when the user's assigned role has the "track work time" flag enabled.
     * Studio owners always return false (they manage employees, they don't log their own hours).
     */
    fun getTrackWorkTime(userId: UserId, studioId: StudioId): Boolean {
        val userEntity = userRepository.findByIdAndStudioId(userId.value, studioId.value)
            ?: return false
        if (userEntity.isOwner) return false
        val customRoleId = userEntity.customRoleId ?: return false
        val roleEntity = roleRepository.findByIdAndStudioId(customRoleId, studioId.value)
            ?: return false
        return roleEntity.trackWorkTime
    }
}
