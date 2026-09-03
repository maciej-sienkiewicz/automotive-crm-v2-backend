package pl.detailing.crm.communication.redirect

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CommunicationRedirectJpaRepository : JpaRepository<CommunicationRedirectEntity, UUID> {
    fun findByStudioId(studioId: UUID): CommunicationRedirectEntity?
}
