package pl.detailing.crm.leads.services

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface LeadServiceTagRepository : JpaRepository<LeadServiceTagEntity, UUID> {

    fun findByLeadId(leadId: UUID): List<LeadServiceTagEntity>

    fun findByLeadIdIn(leadIds: List<UUID>): List<LeadServiceTagEntity>

    fun findByStudioId(studioId: UUID): List<LeadServiceTagEntity>

    @Modifying
    @Query("DELETE FROM LeadServiceTagEntity t WHERE t.leadId = :leadId")
    fun deleteByLeadId(@Param("leadId") leadId: UUID)
}
