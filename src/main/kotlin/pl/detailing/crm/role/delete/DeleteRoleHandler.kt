package pl.detailing.crm.role.delete

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.user.infrastructure.UserRepository

@Service
class DeleteRoleHandler(
    private val roleRepository: RoleRepository,
    private val userRepository: UserRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(studioId: StudioId, roleId: RoleId, requestedBy: UserId, requestedByName: String?) =
        withContext(Dispatchers.IO) {
            val entity = roleRepository.findByIdAndStudioId(roleId.value, studioId.value)
                ?: throw EntityNotFoundException("Rola nie istnieje")

            val assignedUsers = userRepository.findByCustomRoleIdAndStudioId(roleId.value, studioId.value)
            if (assignedUsers.isNotEmpty()) {
                throw BusinessException(
                    "Nie można usunąć roli '${entity.name}' — jest przypisana do ${assignedUsers.size} użytkownika(-ów). " +
                        "Najpierw zmień lub usuń przypisania."
                )
            }

            roleRepository.delete(entity)

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
