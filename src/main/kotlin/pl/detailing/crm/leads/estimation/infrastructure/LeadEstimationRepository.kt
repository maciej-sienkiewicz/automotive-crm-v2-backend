package pl.detailing.crm.leads.estimation.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface LeadEstimationRepository : JpaRepository<LeadEstimationEntity, UUID> {

    @Query("SELECT e FROM LeadEstimationEntity e WHERE e.leadId = :leadId")
    fun findByLeadId(@Param("leadId") leadId: UUID): LeadEstimationEntity?

    @Query("SELECT e FROM LeadEstimationEntity e WHERE e.studioId = :studioId AND e.leadId IN :leadIds")
    fun findByStudioIdAndLeadIdIn(
        @Param("studioId") studioId: UUID,
        @Param("leadIds") leadIds: List<UUID>
    ): List<LeadEstimationEntity>
}
