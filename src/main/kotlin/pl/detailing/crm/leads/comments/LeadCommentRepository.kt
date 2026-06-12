package pl.detailing.crm.leads.comments

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface LeadCommentRepository : JpaRepository<LeadCommentEntity, UUID> {

    @Query("""
        SELECT c FROM LeadCommentEntity c
        WHERE c.leadId = :leadId
        AND c.deletedAt IS NULL
        ORDER BY c.createdAt ASC
    """)
    fun findActiveByLeadId(@Param("leadId") leadId: UUID): List<LeadCommentEntity>

    @Query("""
        SELECT c FROM LeadCommentEntity c
        WHERE c.id = :id
        AND c.studioId = :studioId
        AND c.deletedAt IS NULL
    """)
    fun findActiveByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): LeadCommentEntity?
}
