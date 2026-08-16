package pl.detailing.crm.role.delete

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.role.permission.PermissionSnapshotCache
import pl.detailing.crm.shared.*
import pl.detailing.crm.user.infrastructure.UserRepository

@Service
class DeleteRoleHandler(
    private val roleRepository: RoleRepository,
    private val userRepository: UserRepository,
    private val auditService: AuditService,
    private val permissionSnapshotCache: PermissionSnapshotCache
) {
    /**
     * Deletes a role, optionally handing its holders over first.
     *
     * [reassignTo] is what turns a dead end into a decision: null means "the role must
     * already be free" and still refuses, [ClearAssignment] drops the holders to no role,
     * and a target role moves them. Hand-over and delete share one transaction, so the
     * team can never be left pointing at a role that no longer exists.
     */
    @Transactional
    suspend fun handle(
        studioId: StudioId,
        roleId: RoleId,
        requestedBy: UserId,
        requestedByName: String?,
        reassignTo: ReassignTarget? = null
    ) = withContext(Dispatchers.IO) {
        val entity = roleRepository.findByIdAndStudioId(roleId.value, studioId.value)
            ?: throw EntityNotFoundException("Rola nie istnieje")

        val assignedUsers = userRepository.findByCustomRoleIdAndStudioId(roleId.value, studioId.value)

        if (assignedUsers.isNotEmpty()) {
            if (reassignTo == null) {
                throw ValidationException(
                    "Nie można usunąć roli '${entity.name}' — jest przypisana do ${assignedUsers.size} użytkownika(-ów). " +
                        "Najpierw zmień lub usuń przypisania."
                )
            }

            val targetRoleId = when (reassignTo) {
                is ReassignTarget.ClearAssignment -> null
                is ReassignTarget.ToRole -> {
                    if (reassignTo.roleId == roleId) {
                        throw ValidationException("Nie można przenieść pracowników na rolę, która jest usuwana")
                    }
                    roleRepository.findByIdAndStudioId(reassignTo.roleId.value, studioId.value)
                        ?: throw EntityNotFoundException("Rola docelowa nie istnieje")
                    reassignTo.roleId.value
                }
            }

            userRepository.reassignCustomRole(
                sourceRoleId = roleId.value,
                targetRoleId = targetRoleId,
                studioId = studioId.value
            )

            assignedUsers.forEach { user ->
                auditService.log(LogAuditCommand(
                    studioId = studioId,
                    userId = requestedBy,
                    userDisplayName = requestedByName ?: "",
                    module = AuditModule.USER,
                    entityId = user.id.toString(),
                    entityDisplayName = "${user.firstName} ${user.lastName}",
                    action = AuditAction.UPDATE,
                    changes = listOf(FieldChange("customRoleId", roleId.value.toString(), targetRoleId?.toString()))
                ))
            }
        }

        roleRepository.delete(entity)

        // Permissions changed for everyone we just moved; the per-user snapshots would
        // otherwise keep serving the deleted role until they expire.
        if (assignedUsers.isNotEmpty()) {
            permissionSnapshotCache.evictStudio(studioId)
        }

        auditService.log(LogAuditCommand(
            studioId = studioId,
            userId = requestedBy,
            userDisplayName = requestedByName ?: "",
            module = AuditModule.USER,
            entityId = roleId.value.toString(),
            entityDisplayName = entity.name,
            action = AuditAction.DELETE,
            changes = emptyList()
        ))
    }
}

/** Where the holders of a role being deleted should land. */
sealed interface ReassignTarget {
    /** Move everyone onto another existing role. */
    data class ToRole(val roleId: RoleId) : ReassignTarget

    /** Leave everyone without a role — they keep their account but lose access. */
    data object ClearAssignment : ReassignTarget
}
