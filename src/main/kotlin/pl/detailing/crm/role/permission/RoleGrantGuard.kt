package pl.detailing.crm.role.permission

import org.springframework.stereotype.Component
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.domain.PermissionHierarchy
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

/**
 * Privilege-escalation guard for role management.
 *
 * `EMPLOYEES_MANAGE` lets a user create, edit and assign roles — but without these
 * checks that single permission was worth every other one: edit your own role (or
 * create a new one) with the full permission list, assign it to yourself, done.
 *
 * Rules (owners are exempt — they hold everything by definition):
 *  - a user may only grant permissions they currently hold themselves,
 *  - a user may never change their own role assignment,
 *  - a user may only assign a role whose permissions they hold themselves.
 *
 * "Currently hold" is the effective set from [PermissionCheckService] — after feature
 * entitlements — so a permission whose module is not in the studio's plan cannot be
 * handed out either.
 */
@Component
class RoleGrantGuard(
    private val permissionCheckService: PermissionCheckService
) {

    /** Throws when [requested] contains any permission the requester does not hold. */
    fun assertCanGrant(requestedBy: UserId, studioId: StudioId, requested: Set<Permission>) {
        val held = permissionCheckService.getPermissions(requestedBy, studioId) ?: return // owner
        val exceeding = PermissionHierarchy.close(requested) - held
        if (exceeding.isNotEmpty()) {
            throw ForbiddenException(
                "Nie możesz nadać uprawnień, których sam nie posiadasz: " +
                    exceeding.joinToString(", ") { it.displayName }
            )
        }
    }

    /**
     * Throws when a non-owner tries to change their own role, or to assign a role
     * carrying permissions they do not hold. [rolePermissions] is null when the
     * assignment is being cleared.
     */
    fun assertCanAssign(
        requestedBy: UserId,
        studioId: StudioId,
        targetUserId: UserId,
        rolePermissions: Set<Permission>?
    ) {
        val held = permissionCheckService.getPermissions(requestedBy, studioId) ?: return // owner
        if (targetUserId == requestedBy) {
            throw ForbiddenException("Nie możesz zmienić własnej roli — poproś właściciela")
        }
        if (rolePermissions != null) {
            val exceeding = rolePermissions - held
            if (exceeding.isNotEmpty()) {
                throw ForbiddenException(
                    "Nie możesz przypisać roli z uprawnieniami, których sam nie posiadasz: " +
                        exceeding.joinToString(", ") { it.displayName }
                )
            }
        }
    }
}
