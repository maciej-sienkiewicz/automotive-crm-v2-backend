package pl.detailing.crm.leads.comments

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
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

    /**
     * Re-attach a single comment to another lead (used when splitting a comment into its own lead).
     * Bulk update — authorship and timestamps are preserved.
     */
    @Modifying
    @Query("""
        UPDATE LeadCommentEntity c
        SET c.leadId = :toLeadId
        WHERE c.id = :commentId
        AND c.deletedAt IS NULL
    """)
    fun reassignComment(
        @Param("commentId") commentId: UUID,
        @Param("toLeadId") toLeadId: UUID
    ): Int

    /**
     * Move all active comments from one lead to another (used when merging two leads).
     * Bulk update — authorship and timestamps are preserved.
     */
    @Modifying
    @Query("""
        UPDATE LeadCommentEntity c
        SET c.leadId = :toLeadId
        WHERE c.leadId = :fromLeadId
        AND c.deletedAt IS NULL
    """)
    fun reassignComments(
        @Param("fromLeadId") fromLeadId: UUID,
        @Param("toLeadId") toLeadId: UUID
    ): Int
}
