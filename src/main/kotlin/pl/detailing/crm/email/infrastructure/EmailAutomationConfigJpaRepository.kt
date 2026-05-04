package pl.detailing.crm.email.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface EmailAutomationConfigJpaRepository : JpaRepository<EmailAutomationConfigEntity, UUID> {

    @Query("SELECT e FROM EmailAutomationConfigEntity e WHERE e.studioId = :studioId")
    fun findByStudioId(@Param("studioId") studioId: UUID): EmailAutomationConfigEntity?
}
