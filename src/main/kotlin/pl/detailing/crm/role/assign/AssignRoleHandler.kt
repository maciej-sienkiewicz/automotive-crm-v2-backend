package pl.detailing.crm.role.assign

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.user.infrastructure.UserRepository

@Service
class AssignRoleHandler(
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val auditService: AuditService
) {
    /**
     * Assigns [roleId] to the user [userId] within [studioId].
     * Pass null for [roleId] to remove the custom role assignment.
     */
    @Transactional
    suspend fun handle(
        studioId: StudioId,
        userId: UserId,
        roleId: RoleId?,
        requestedBy: UserId,
        requestedByName: String?
    ) = withContext(Dispatchers.IO) {
        val userEntity = userRepository.findByIdAndStudioId(userId.value, studioId.value)
            ?: throw EntityNotFoundException("Użytkownik nie istnieje")

        if (userEntity.role == UserRole.OWNER) {
            throw BusinessException("Właściciel firmy nie może mieć przypisanej roli — ma pełne uprawnienia")
        }

        if (roleId != null) {
            roleRepository.findByIdAndStudioId(roleId.value, studioId.value)
                ?: throw EntityNotFoundException("Rola nie istnieje")
        }

        val previousRoleId = userEntity.customRoleId
        userEntity.customRoleId = roleId?.value

        userRepository.save(userEntity)

        auditService.log(LogAuditCommand(
            studioId = studioId,
            userId = requestedBy,
            userDisplayName = requestedByName ?: "",
            module = AuditModule.USER,
            entityId = userId.value.toString(),
            entityDisplayName = "${userEntity.firstName} ${userEntity.lastName}",
            action = AuditAction.UPDATE,
            changes = listOf(FieldChange("customRoleId", previousRoleId?.toString(), roleId?.toString()))
        ))
    }
}
