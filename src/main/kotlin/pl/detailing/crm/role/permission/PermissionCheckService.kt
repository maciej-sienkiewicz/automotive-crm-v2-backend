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
 * - [Permission.VISITS_CREATE] → [Permission.CUSTOMERS_MANAGE] (+ its ancestors) and
 *   [Permission.SERVICES_VIEW] — booking a visit means entering the customer's data and
 *   composing services from the catalog.
 * - [Permission.VISITS_DOCUMENTS_MANAGE], [Permission.FINANCE_INVOICES] and
 *   [Permission.COMMUNICATION_SEND] → [Permission.CUSTOMERS_VIEW] — documents, invoices
 *   and outbound messages ARE personal data; masking them would make the permission useless.
 * - [Permission.FINANCE_INVOICES] → [Permission.VISITS_VIEW] — an income document is
 *   created from a visit.
 * - Any Finance or Statistics permission → [Permission.SERVICES_VIEW] implied.
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

        // toDomain closes over the tree (adds ancestors); expansion runs before feature
        // filtering so implied permissions are feature-gated like directly granted ones.
        val expanded = expandCrossModule(roleEntity.toDomain().permissions)

        return expanded.filterTo(mutableSetOf()) { permission ->
            val requiredFeature = permission.effectiveFeatureKey ?: return@filterTo true
            entitlementService.hasFeature(studioId, requiredFeature)
        }
    }

    /**
     * Applies cross-module access rules that cannot be expressed in the single-module tree.
     * The result is re-closed over the tree so implied permissions carry their ancestors.
     */
    private fun expandCrossModule(permissions: Set<Permission>): Set<Permission> {
        val result = permissions.toMutableSet()

        // Booking a visit = entering the (possibly new) customer's and vehicle's data,
        // and composing services from the catalog.
        if (Permission.VISITS_CREATE in result) {
            result.add(Permission.CUSTOMERS_MANAGE)
            result.add(Permission.SERVICES_VIEW)
        }

        // Person-centric capabilities: their content IS customer personal data, so they
        // imply the personal-data permission (CUSTOMERS_VIEW) — 403/masking would make
        // the granted permission useless.
        if (Permission.VISITS_DOCUMENTS_MANAGE in result ||
            Permission.FINANCE_INVOICES in result ||
            Permission.COMMUNICATION_SEND in result
        ) {
            result.add(Permission.CUSTOMERS_VIEW)
        }

        // An income document is created from a visit.
        if (Permission.FINANCE_INVOICES in result) {
            result.add(Permission.VISITS_VIEW)
        }

        // Finance and statistics reporting reference the service catalog.
        if (result.any { it.module == PermissionModule.FINANCE || it.module == PermissionModule.STATISTICS }) {
            result.add(Permission.SERVICES_VIEW)
        }

        return PermissionHierarchy.close(result)
    }
}
