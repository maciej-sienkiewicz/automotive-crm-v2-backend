package pl.detailing.crm.leads.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import java.time.Instant
import java.util.*

@Repository
interface LeadRepository : JpaRepository<LeadEntity, UUID> {

    /**
     * Find all leads for a studio with optional filters
     */
    @Query(value = """
        SELECT * FROM leads l
        WHERE l.studio_id = CAST(:studioId AS uuid)
        AND (CAST(:statuses AS text) IS NULL OR l.status IN (:statuses))
        AND (CAST(:sources AS text) IS NULL OR l.source IN (:sources))
        AND (CAST(:search AS text) IS NULL OR 
             l.contact_identifier ILIKE '%' || CAST(:search AS text) || '%' OR
             l.customer_name ILIKE '%' || CAST(:search AS text) || '%' OR
             l.initial_message ILIKE '%' || CAST(:search AS text) || '%')
    """, 
    countQuery = """
        SELECT count(*) FROM leads l
        WHERE l.studio_id = CAST(:studioId AS uuid)
        AND (CAST(:statuses AS text) IS NULL OR l.status IN (:statuses))
        AND (CAST(:sources AS text) IS NULL OR l.source IN (:sources))
        AND (CAST(:search AS text) IS NULL OR 
             l.contact_identifier ILIKE '%' || CAST(:search AS text) || '%' OR
             l.customer_name ILIKE '%' || CAST(:search AS text) || '%' OR
             l.initial_message ILIKE '%' || CAST(:search AS text) || '%')
    """,
    nativeQuery = true)
    fun findByStudioIdWithFilters(
        @Param("studioId") studioId: UUID,
        @Param("statuses") statuses: List<String>?,
        @Param("sources") sources: List<String>?,
        @Param("search") search: String?,
        pageable: Pageable
    ): Page<LeadEntity>

    /**
     * Find all leads by studio and status
     */
    fun findByStudioIdAndStatus(studioId: UUID, status: LeadStatus): List<LeadEntity>

    /**
     * Find all leads by studio and source
     */
    fun findByStudioIdAndSourceIn(studioId: UUID, sources: List<LeadSource>): List<LeadEntity>

    /**
     * Count leads by studio and status
     */
    fun countByStudioIdAndStatus(studioId: UUID, status: LeadStatus): Long

    /**
     * Find leads created after a specific date
     */
    fun findByStudioIdAndCreatedAtAfter(studioId: UUID, createdAt: Instant): List<LeadEntity>

    /**
     * Find converted leads updated after a specific date
     */
    fun findByStudioIdAndStatusAndUpdatedAtAfter(
        studioId: UUID,
        status: LeadStatus,
        updatedAt: Instant
    ): List<LeadEntity>

    /**
     * Find leads by studio, status and source filter
     */
    fun findByStudioIdAndStatusAndSourceIn(
        studioId: UUID,
        status: LeadStatus,
        sources: List<LeadSource>
    ): List<LeadEntity>

    /**
     * Find leads by studio with source filter
     */
    @Query("""
        SELECT l FROM LeadEntity l
        WHERE l.studioId = :studioId
        AND (:sources IS NULL OR l.source IN :sources)
    """)
    fun findByStudioIdWithSourceFilter(
        @Param("studioId") studioId: UUID,
        @Param("sources") sources: List<LeadSource>?
    ): List<LeadEntity>
}
