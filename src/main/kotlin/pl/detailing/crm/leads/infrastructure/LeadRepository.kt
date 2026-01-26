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
    @Query("""
        SELECT l FROM LeadEntity l
        WHERE l.studioId = :studioId
        AND (:statuses IS NULL OR l.status IN :statuses)
        AND (:sources IS NULL OR l.source IN :sources)
        AND (:search IS NULL OR 
             LOWER(l.contactIdentifier) LIKE LOWER(CONCAT('%', :search, '%')) OR
             LOWER(l.customerName) LIKE LOWER(CONCAT('%', :search, '%')) OR
             LOWER(l.initialMessage) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY 
            CASE WHEN l.requiresVerification = true THEN 0 ELSE 1 END,
            l.createdAt DESC
    """)
    fun findByStudioIdWithFilters(
        @Param("studioId") studioId: UUID,
        @Param("statuses") statuses: List<LeadStatus>?,
        @Param("sources") sources: List<LeadSource>?,
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
