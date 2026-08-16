package pl.detailing.crm.role

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.assign.AssignRoleHandler
import pl.detailing.crm.role.create.CreateRoleCommand
import pl.detailing.crm.role.create.CreateRoleHandler
import pl.detailing.crm.role.delete.DeleteRoleHandler
import pl.detailing.crm.role.delete.ReassignTarget
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.domain.PermissionHierarchy
import pl.detailing.crm.role.domain.PermissionModule
import pl.detailing.crm.role.domain.Role
import pl.detailing.crm.role.get.GetRoleHandler
import pl.detailing.crm.role.list.ListRolesHandler
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.role.update.UpdateRoleCommand
import pl.detailing.crm.role.update.UpdateRoleHandler
import pl.detailing.crm.shared.*
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Instant
import java.util.UUID

// Role administration is part of account management (EMPLOYEES_MANAGE);
// deleting a role additionally stays owner-only (checked in the handler below).
@RequiresPermission(Permission.EMPLOYEES_MANAGE)
@RestController
@RequestMapping("/api/v1/roles")
class RoleController(
    private val createRoleHandler: CreateRoleHandler,
    private val updateRoleHandler: UpdateRoleHandler,
    private val deleteRoleHandler: DeleteRoleHandler,
    private val getRoleHandler: GetRoleHandler,
    private val listRolesHandler: ListRolesHandler,
    private val assignRoleHandler: AssignRoleHandler,
    private val userRepository: UserRepository
) {

    // ── Permission catalog ────────────────────────────────────────────────────

    /**
     * Returns the full hardcoded permission catalog as a **tree**, grouped by module.
     * A node's children require the node itself; a node may additionally imply
     * permissions outside its branch ([PermissionNodeResponse.implies]). The frontend
     * renders the hierarchy directly and cascades checkbox selection along both the
     * parent chain and the implication edges.
     */
    @GetMapping("/permissions")
    fun getPermissionCatalog(): ResponseEntity<List<PermissionModuleTreeResponse>> {
        val modules = PermissionModule.entries.mapNotNull { module ->
            val roots = PermissionHierarchy.rootsOf(module)
            if (roots.isEmpty()) return@mapNotNull null
            PermissionModuleTreeResponse(
                module = module.name,
                displayName = module.displayName,
                featureKey = module.featureKey?.name,
                nodes = roots.map { it.toNodeResponse() }
            )
        }
        return ResponseEntity.ok(modules)
    }

    // ── Role CRUD ─────────────────────────────────────────────────────────────

    @GetMapping
    fun listRoles(): ResponseEntity<List<RoleResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val roles = listRolesHandler.handle(principal.studioId)
        val counts = userRepository.countAssignmentsByRole(principal.studioId.value)
            .associate { it.roleId to it.userCount }
        ResponseEntity.ok(roles.map { it.toResponse(counts[it.id.value] ?: 0L) })
    }

    @GetMapping("/{roleId}")
    fun getRole(@PathVariable roleId: String): ResponseEntity<RoleResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val role = getRoleHandler.handle(RoleId.fromString(roleId), principal.studioId)
        val assigned = userRepository
            .findByCustomRoleIdAndStudioId(role.id.value, principal.studioId.value).size.toLong()
        ResponseEntity.ok(role.toResponse(assigned))
    }

    /**
     * Who currently holds this role. Backs the "review the people" step of the
     * hand-over dialog, so deleting a role can show names instead of a bare count.
     */
    @GetMapping("/{roleId}/users")
    fun listRoleUsers(@PathVariable roleId: String): ResponseEntity<List<RoleUserResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val role = getRoleHandler.handle(RoleId.fromString(roleId), principal.studioId)
        val users = userRepository
            .findByCustomRoleIdAndStudioId(role.id.value, principal.studioId.value)
            .sortedWith(compareBy({ it.lastName }, { it.firstName }))
            .map {
                RoleUserResponse(
                    userId = it.id.toString(),
                    firstName = it.firstName,
                    lastName = it.lastName,
                    fullName = "${it.firstName} ${it.lastName}".trim(),
                    email = it.email,
                    isActive = it.isActive
                )
            }
        ResponseEntity.ok(users)
    }

    @PostMapping
    fun createRole(@RequestBody request: CreateRoleRequest): ResponseEntity<Map<String, String>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val permissions = request.permissions.map { code ->
            Permission.fromApiCode(code) ?: throw ValidationException("Nieznane uprawnienie: '$code'")
        }.toSet()

        val roleId = createRoleHandler.handle(
            CreateRoleCommand(
                studioId = principal.studioId,
                requestedBy = principal.userId,
                requestedByName = principal.fullName,
                name = request.name,
                description = request.description,
                permissions = permissions,
                trackWorkTime = request.trackWorkTime
            )
        )
        ResponseEntity.status(HttpStatus.CREATED).body(mapOf("roleId" to roleId.toString()))
    }

    @PutMapping("/{roleId}")
    fun updateRole(
        @PathVariable roleId: String,
        @RequestBody request: UpdateRoleRequest
    ): ResponseEntity<RoleResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val permissions = request.permissions.map { code ->
            Permission.fromApiCode(code) ?: throw ValidationException("Nieznane uprawnienie: '$code'")
        }.toSet()

        updateRoleHandler.handle(
            UpdateRoleCommand(
                studioId = principal.studioId,
                requestedBy = principal.userId,
                requestedByName = principal.fullName,
                roleId = RoleId.fromString(roleId),
                name = request.name,
                description = request.description,
                permissions = permissions,
                trackWorkTime = request.trackWorkTime
            )
        )

        val role = getRoleHandler.handle(RoleId.fromString(roleId), principal.studioId)
        ResponseEntity.ok(role.toResponse())
    }

    /**
     * Deletes a role.
     *
     * [reassignTo] decides what happens to the people holding it: omit it and a role
     * with holders is refused (unchanged behaviour), pass another role's id to move them,
     * or pass [REASSIGN_TARGET_NONE] to leave them without a role. Hand-over and delete
     * are one transaction.
     */
    @DeleteMapping("/{roleId}")
    fun deleteRole(
        @PathVariable roleId: String,
        @RequestParam(required = false) reassignTo: String?
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (!principal.isOwner) {
            throw ForbiddenException("Tylko właściciel może usuwać role")
        }

        deleteRoleHandler.handle(
            studioId = principal.studioId,
            roleId = RoleId.fromString(roleId),
            requestedBy = principal.userId,
            requestedByName = principal.fullName,
            reassignTo = reassignTo?.trim()?.takeIf { it.isNotEmpty() }?.let { target ->
                if (target.equals(REASSIGN_TARGET_NONE, ignoreCase = true)) {
                    ReassignTarget.ClearAssignment
                } else {
                    ReassignTarget.ToRole(RoleId.fromString(target))
                }
            }
        )
        ResponseEntity.noContent().build()
    }

    // ── Role assignment ───────────────────────────────────────────────────────

    /**
     * Assigns a custom role to a user account.
     * The user is identified by their userId (not employeeId).
     */
    @PutMapping("/assign/{userId}")
    fun assignRole(
        @PathVariable userId: String,
        @RequestBody request: AssignRoleRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        assignRoleHandler.handle(
            studioId = principal.studioId,
            userId = UserId.fromString(userId),
            roleId = request.roleId?.let { RoleId.fromString(it) },
            requestedBy = principal.userId,
            requestedByName = principal.fullName
        )
        ResponseEntity.noContent().build()
    }

    companion object {
        /** `?reassignTo=none` — holders keep their account but end up with no role. */
        const val REASSIGN_TARGET_NONE = "none"
    }
}

// ── Request DTOs ──────────────────────────────────────────────────────────────

data class CreateRoleRequest(
    val name: String,
    val description: String?,
    val permissions: List<String>,
    val trackWorkTime: Boolean = false
)

data class UpdateRoleRequest(
    val name: String,
    val description: String?,
    val permissions: List<String>,
    val trackWorkTime: Boolean = false
)

data class AssignRoleRequest(
    /** Null removes the custom role assignment. */
    val roleId: String?
)

// ── Response DTOs ─────────────────────────────────────────────────────────────

data class PermissionModuleTreeResponse(
    val module: String,
    val displayName: String,
    /** When non-null, this module requires the studio to have this feature key enabled. */
    val featureKey: String?,
    /** Root permissions of the module; each node nests its dependents in [PermissionNodeResponse.children]. */
    val nodes: List<PermissionNodeResponse>
)

data class PermissionNodeResponse(
    val code: String,
    val displayName: String,
    val description: String?,
    /** Presentational group header among siblings (e.g. "Usługi"); null = ungrouped. */
    val section: String?,
    /** When non-null, this permission is gated by a different feature than its module. */
    val featureKey: String?,
    /**
     * Codes this permission additionally requires beyond its parent chain (possibly in
     * another branch or module). Selecting this node must also select them, with their
     * own ancestors and implications.
     */
    val implies: List<String>,
    /** Permissions that require this one. Selecting a child must select the whole ancestor chain. */
    val children: List<PermissionNodeResponse>
)

private fun Permission.toNodeResponse(): PermissionNodeResponse = PermissionNodeResponse(
    code = name,
    displayName = displayName,
    description = description,
    section = section,
    featureKey = effectiveFeatureKey?.takeIf { it != module.featureKey }?.name,
    implies = PermissionHierarchy.impliesOf(this).map { it.name },
    children = PermissionHierarchy.childrenOf(this).map { it.toNodeResponse() }
)

data class RoleResponse(
    val id: String,
    val name: String,
    val description: String?,
    val permissions: List<RolePermissionResponse>,
    val trackWorkTime: Boolean,
    /**
     * How many accounts currently hold this role. Lets the UI show the conflict
     * ("used by 4 people") before someone tries to delete it, instead of after.
     */
    val assignedUserCount: Long,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class RolePermissionResponse(
    val code: String,
    val displayName: String,
    val module: String,
    val moduleDisplayName: String
)

data class RoleUserResponse(
    val userId: String,
    val firstName: String,
    val lastName: String,
    val fullName: String,
    val email: String,
    val isActive: Boolean
)

// ── Domain → Response mapping ─────────────────────────────────────────────────

private fun Role.toResponse(assignedUserCount: Long = 0L) = RoleResponse(
    id = id.toString(),
    name = name,
    description = description,
    permissions = permissions
        .sortedWith(compareBy({ it.module.name }, { it.name }))
        .map { p ->
            RolePermissionResponse(
                code = p.name,
                displayName = p.displayName,
                module = p.module.name,
                moduleDisplayName = p.module.displayName
            )
        },
    trackWorkTime = trackWorkTime,
    assignedUserCount = assignedUserCount,
    createdAt = createdAt,
    updatedAt = updatedAt
)
