package pl.detailing.crm.visitcard.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface VisitCardTokenRepository : JpaRepository<VisitCardTokenEntity, UUID> {

    fun findByToken(token: String): VisitCardTokenEntity?

    fun findByVisitIdAndStudioId(visitId: UUID, studioId: UUID): VisitCardTokenEntity?
}
