package pl.detailing.crm.ksef.credentials

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface KsefCredentialsRepository : JpaRepository<KsefCredentialsEntity, UUID> {
    fun findByStudioId(studioId: UUID): KsefCredentialsEntity?
    fun deleteByStudioId(studioId: UUID)
    fun existsByStudioId(studioId: UUID): Boolean
}
