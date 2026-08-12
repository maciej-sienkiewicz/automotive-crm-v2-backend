package pl.detailing.crm.role.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.role.domain.PermissionHierarchy
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.role.permission.PermissionSnapshotCache
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ValidationException
import java.time.Instant

@Service
class UpdateRoleHandler(
    private val roleRepository: RoleRepository,
    private val auditService: AuditService,
    private val permissionSnapshotCache: PermissionSnapshotCache
) {
    @Transactional
    suspend fun handle(command: UpdateRoleCommand) = withContext(Dispatchers.IO) {
        val name = command.name.trim()
        if (name.isBlank()) throw ValidationException("Nazwa roli nie może być pusta")

        val entity = roleRepository.findByIdAndStudioId(command.roleId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Rola nie istnieje")

        if (roleRepository.existsByStudioIdAndNameExcluding(command.studioId.value, name, command.roleId.value)) {
            throw ValidationException("Rola o nazwie '$name' już istnieje w tej firmie")
        }

        val oldName = entity.name
        val oldPermissionsCount = entity.permissions.size

        // Auto-complete the set with every ancestor in the permission tree so the stored
        // role always forms complete root-to-node paths. Runtime checks stay trivial.
        val effectivePermissions = PermissionHierarchy.close(command.permissions)

        entity.name = name
        entity.description = command.description?.trim()
        entity.permissions.clear()
        entity.permissions.addAll(effectivePermissions.map { it.name })
        entity.trackWorkTime = command.trackWorkTime
        entity.updatedAt = Instant.now()

        roleRepository.save(entity)

        // Any number of users may hold this role — drop every cached snapshot in the studio
        // so the new permission set is enforced immediately.
        permissionSnapshotCache.evictStudio(command.studioId)

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.requestedBy,
            userDisplayName = command.requestedByName ?: "",
            module = AuditModule.USER,
            entityId = command.roleId.value.toString(),
            entityDisplayName = name,
            action = AuditAction.UPDATE,
            changes = listOf(
                FieldChange("name", oldName, name),
                FieldChange("permissions", oldPermissionsCount.toString(), effectivePermissions.size.toString())
            )
        ))
    }
}
