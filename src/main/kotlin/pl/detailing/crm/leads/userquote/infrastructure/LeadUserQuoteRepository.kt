package pl.detailing.crm.leads.userquote.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface LeadUserQuoteRepository : JpaRepository<LeadUserQuoteEntity, UUID> {
    fun findByLeadId(leadId: UUID): LeadUserQuoteEntity?
    fun deleteByLeadId(leadId: UUID)
}
