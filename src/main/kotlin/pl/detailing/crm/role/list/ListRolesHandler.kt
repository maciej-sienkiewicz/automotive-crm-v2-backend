package pl.detailing.crm.role.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.role.domain.Role
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.shared.StudioId

@Service
class ListRolesHandler(private val roleRepository: RoleRepository) {
    suspend fun handle(studioId: StudioId): List<Role> = withContext(Dispatchers.IO) {
        roleRepository.findByStudioId(studioId.value).map { it.toDomain() }
    }
}
