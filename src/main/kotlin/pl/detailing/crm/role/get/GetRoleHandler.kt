package pl.detailing.crm.role.get

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.role.domain.Role
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.RoleId
import pl.detailing.crm.shared.StudioId

@Service
class GetRoleHandler(private val roleRepository: RoleRepository) {
    suspend fun handle(roleId: RoleId, studioId: StudioId): Role = withContext(Dispatchers.IO) {
        (roleRepository.findByIdAndStudioId(roleId.value, studioId.value)
            ?: throw EntityNotFoundException("Rola nie istnieje")).toDomain()
    }
}
