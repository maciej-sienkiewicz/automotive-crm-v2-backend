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
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.domain.PermissionModule
import pl.detailing.crm.role.domain.Role
import pl.detailing.crm.role.get.GetRoleHandler
import pl.detailing.crm.role.list.ListRolesHandler
import pl.detailing.crm.role.update.UpdateRoleCommand
import pl.detailing.crm.role.update.UpdateRoleHandler
import pl.detailing.crm.shared.*
import java.time.Instant

@RestController
@RequestMapping("/api/v1/roles")
class RoleController(
    private val createRoleHandler: CreateRoleHandler,
    private val updateRoleHandler: UpdateRoleHandler,
    private val deleteRoleHandler: DeleteRoleHandler,
    private val getRoleHandler: GetRoleHandler,
    private val listRolesHandler: ListRolesHandler,
    private val assignRoleHandler: AssignRoleHandler
) {

    // ── Permission catalog ────────────────────────────────────────────────────

    /**
     * Returns the full hardcoded permission catalog grouped by module.
     * Frontend uses this to render the role editor with module sections and checkboxes.
     */
    @GetMapping("/permissions")
    fun getPermissionCatalog(): ResponseEntity<List<PermissionModuleResponse>> {
        val grouped = Permission.entries
            .groupBy { it.module }
            .map { (module, perms) ->
                PermissionModuleResponse(
                    module = module.name,
                    displayName = module.displayName,
                    featureKey = module.featureKey?.name,
                    permissions = perms.map { p ->
                        PermissionEntryResponse(code = p.name, displayName = p.displayName)
                    }
                )
            }
        return ResponseEntity.ok(grouped)
    }

    // ── Role CRUD ─────────────────────────────────────────────────────────────

    @GetMapping
    fun listRoles(): ResponseEntity<List<RoleResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val roles = listRolesHandler.handle(principal.studioId)
        ResponseEntity.ok(roles.map { it.toResponse() })
    }

    @GetMapping("/{roleId}")
    fun getRole(@PathVariable roleId: String): ResponseEntity<RoleResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val role = getRoleHandler.handle(RoleId.fromString(roleId), principal.studioId)
        ResponseEntity.ok(role.toResponse())
    }

    @PostMapping
    fun createRole(@RequestBody request: CreateRoleRequest): ResponseEntity<Map<String, String>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Tylko właściciel i menedżer mogą tworzyć role")
        }

        val permissions = request.permissions.map { code ->
            runCatching { Permission.valueOf(code) }.getOrElse {
                throw ValidationException("Nieznane uprawnienie: '$code'")
            }
        }.toSet()

        val roleId = createRoleHandler.handle(
            CreateRoleCommand(
                studioId = principal.studioId,
                requestedBy = principal.userId,
                requestedByName = principal.fullName,
                name = request.name,
                description = request.description,
                permissions = permissions
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
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Tylko właściciel i menedżer mogą edytować role")
        }

        val permissions = request.permissions.map { code ->
            runCatching { Permission.valueOf(code) }.getOrElse {
                throw ValidationException("Nieznane uprawnienie: '$code'")
            }
        }.toSet()

        updateRoleHandler.handle(
            UpdateRoleCommand(
                studioId = principal.studioId,
                requestedBy = principal.userId,
                requestedByName = principal.fullName,
                roleId = RoleId.fromString(roleId),
                name = request.name,
                description = request.description,
                permissions = permissions
            )
        )

        val role = getRoleHandler.handle(RoleId.fromString(roleId), principal.studioId)
        ResponseEntity.ok(role.toResponse())
    }

    @DeleteMapping("/{roleId}")
    fun deleteRole(@PathVariable roleId: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Tylko właściciel może usuwać role")
        }

        deleteRoleHandler.handle(
            studioId = principal.studioId,
            roleId = RoleId.fromString(roleId),
            requestedBy = principal.userId,
            requestedByName = principal.fullName
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
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Tylko właściciel i menedżer mogą przypisywać role")
        }

        assignRoleHandler.handle(
            studioId = principal.studioId,
            userId = UserId.fromString(userId),
            roleId = request.roleId?.let { RoleId.fromString(it) },
            requestedBy = principal.userId,
            requestedByName = principal.fullName
        )
        ResponseEntity.noContent().build()
    }
}

// ── Request DTOs ──────────────────────────────────────────────────────────────

data class CreateRoleRequest(
    val name: String,
    val description: String?,
    val permissions: List<String>
)

data class UpdateRoleRequest(
    val name: String,
    val description: String?,
    val permissions: List<String>
)

data class AssignRoleRequest(
    /** Null removes the custom role assignment. */
    val roleId: String?
)

// ── Response DTOs ─────────────────────────────────────────────────────────────

data class PermissionModuleResponse(
    val module: String,
    val displayName: String,
    /** When non-null, this module requires the studio to have this feature key enabled. */
    val featureKey: String?,
    val permissions: List<PermissionEntryResponse>
)

data class PermissionEntryResponse(
    val code: String,
    val displayName: String
)

data class RoleResponse(
    val id: String,
    val name: String,
    val description: String?,
    val permissions: List<RolePermissionResponse>,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class RolePermissionResponse(
    val code: String,
    val displayName: String,
    val module: String,
    val moduleDisplayName: String
)

// ── Domain → Response mapping ─────────────────────────────────────────────────

private fun Role.toResponse() = RoleResponse(
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
    createdAt = createdAt,
    updatedAt = updatedAt
)
