package pl.detailing.crm.leads.userquote.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface LeadUserQuoteRepository : JpaRepository<LeadUserQuoteEntity, UUID> {
    fun findByLeadId(leadId: UUID): LeadUserQuoteEntity?
    fun deleteByLeadId(leadId: UUID)

    @Query("SELECT q FROM LeadUserQuoteEntity q LEFT JOIN FETCH q.items WHERE q.leadId IN :leadIds")
    fun findByLeadIdIn(@Param("leadIds") leadIds: List<UUID>): List<LeadUserQuoteEntity>
}
