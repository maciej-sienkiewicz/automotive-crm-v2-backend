package pl.detailing.crm.role.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.role.domain.PermissionDependencies
import pl.detailing.crm.role.domain.Role
import pl.detailing.crm.role.infrastructure.RoleEntity
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.shared.RoleId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant

@Service
class CreateRoleHandler(
    private val roleRepository: RoleRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: CreateRoleCommand): RoleId = withContext(Dispatchers.IO) {
        val name = command.name.trim()
        if (name.isBlank()) throw ValidationException("Nazwa roli nie może być pusta")

        if (roleRepository.existsByStudioIdAndName(command.studioId.value, name)) {
            throw ValidationException("Rola o nazwie '$name' już istnieje w tej firmie")
        }

        val role = Role(
            id = RoleId.random(),
            studioId = command.studioId,
            name = name,
            description = command.description?.trim(),
            // Auto-complete the set with every hard prerequisite so the stored role is always
            // internally consistent (e.g. EDIT implies VIEW). Runtime checks stay trivial.
            permissions = PermissionDependencies.close(command.permissions),
            createdBy = command.requestedBy,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        roleRepository.save(RoleEntity.fromDomain(role))

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.requestedBy,
            userDisplayName = command.requestedByName ?: "",
            module = AuditModule.USER,
            entityId = role.id.value.toString(),
            entityDisplayName = role.name,
            action = AuditAction.CREATE,
            changes = listOf(
                FieldChange("name", null, role.name),
                FieldChange("permissions", null, role.permissions.size.toString())
            )
        ))

        role.id
    }
}
