package pl.detailing.crm.leads.quotereply

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface QuoteReplyExampleRepository : JpaRepository<QuoteReplyExampleEntity, UUID> {
    fun findByStudioIdOrderByCreatedAtDesc(studioId: UUID): List<QuoteReplyExampleEntity>
    fun countByStudioId(studioId: UUID): Long
    fun findByIdAndStudioId(id: UUID, studioId: UUID): QuoteReplyExampleEntity?
}
