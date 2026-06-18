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
        AND (CAST(:dateFrom AS timestamptz) IS NULL OR l.created_at >= CAST(:dateFrom AS timestamptz))
        AND (CAST(:dateTo AS timestamptz) IS NULL OR l.created_at < CAST(:dateTo AS timestamptz))
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
        AND (CAST(:dateFrom AS timestamptz) IS NULL OR l.created_at >= CAST(:dateFrom AS timestamptz))
        AND (CAST(:dateTo AS timestamptz) IS NULL OR l.created_at < CAST(:dateTo AS timestamptz))
    """,
    nativeQuery = true)
    fun findByStudioIdWithFilters(
        @Param("studioId") studioId: UUID,
        @Param("statuses") statuses: List<String>?,
        @Param("sources") sources: List<String>?,
        @Param("search") search: String?,
        @Param("dateFrom") dateFrom: Instant?,
        @Param("dateTo") dateTo: Instant?,
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
    @Query(value = """
        SELECT * FROM leads l
        WHERE l.studio_id = CAST(:studioId AS uuid)
        AND (CAST(:sources AS text) IS NULL OR l.source IN (:sources))
        AND (CAST(:dateFrom AS timestamptz) IS NULL OR l.created_at >= CAST(:dateFrom AS timestamptz))
        AND (CAST(:dateTo AS timestamptz) IS NULL OR l.created_at < CAST(:dateTo AS timestamptz))
    """, nativeQuery = true)
    fun findByStudioIdWithSourceFilter(
        @Param("studioId") studioId: UUID,
        @Param("sources") sources: List<String>?,
        @Param("dateFrom") dateFrom: Instant? = null,
        @Param("dateTo") dateTo: Instant? = null
    ): List<LeadEntity>

    /**
     * Find a lead linked to a specific appointment (used for lead status sync on appointment events)
     */
    /**
     * Find active (non-terminal) leads from a given contact address within a time window.
     * Used to detect email reply threads instead of creating duplicate leads.
     */
    @Query(value = """
        SELECT * FROM leads l
        WHERE l.studio_id = CAST(:studioId AS uuid)
        AND l.contact_identifier = :contactIdentifier
        AND l.created_at >= CAST(:since AS timestamptz)
        ORDER BY l.created_at DESC
        LIMIT 1
    """, nativeQuery = true)
    fun findLatestActiveByContactIdentifier(
        @Param("studioId") studioId: UUID,
        @Param("contactIdentifier") contactIdentifier: String,
        @Param("since") since: Instant
    ): LeadEntity?

    fun findByAppointmentId(appointmentId: UUID): LeadEntity?

    fun findByVisitId(visitId: UUID): LeadEntity?

    /**
     * Find leads with extended filters: services, value range, assigned user
     */
    @Query(value = """
        SELECT DISTINCT l.* FROM leads l
        LEFT JOIN lead_service_tags lst ON lst.lead_id = l.id
        WHERE l.studio_id = CAST(:studioId AS uuid)
        AND (CAST(:statuses AS text) IS NULL OR l.status IN (:statuses))
        AND (CAST(:sources AS text) IS NULL OR l.source IN (:sources))
        AND (CAST(:search AS text) IS NULL OR
             l.contact_identifier ILIKE '%' || CAST(:search AS text) || '%' OR
             l.customer_name ILIKE '%' || CAST(:search AS text) || '%' OR
             l.initial_message ILIKE '%' || CAST(:search AS text) || '%')
        AND (CAST(:dateFrom AS timestamptz) IS NULL OR l.created_at >= CAST(:dateFrom AS timestamptz))
        AND (CAST(:dateTo AS timestamptz) IS NULL OR l.created_at < CAST(:dateTo AS timestamptz))
        AND (CAST(:valueMin AS bigint) IS NULL OR l.estimated_value >= CAST(:valueMin AS bigint))
        AND (CAST(:valueMax AS bigint) IS NULL OR l.estimated_value <= CAST(:valueMax AS bigint))
        AND (CAST(:assignedUserId AS text) IS NULL OR l.assigned_user_id = CAST(:assignedUserId AS uuid))
        AND (:serviceIds IS NULL OR lst.service_id IN (:serviceIds))
    """,
    countQuery = """
        SELECT COUNT(DISTINCT l.id) FROM leads l
        LEFT JOIN lead_service_tags lst ON lst.lead_id = l.id
        WHERE l.studio_id = CAST(:studioId AS uuid)
        AND (CAST(:statuses AS text) IS NULL OR l.status IN (:statuses))
        AND (CAST(:sources AS text) IS NULL OR l.source IN (:sources))
        AND (CAST(:search AS text) IS NULL OR
             l.contact_identifier ILIKE '%' || CAST(:search AS text) || '%' OR
             l.customer_name ILIKE '%' || CAST(:search AS text) || '%' OR
             l.initial_message ILIKE '%' || CAST(:search AS text) || '%')
        AND (CAST(:dateFrom AS timestamptz) IS NULL OR l.created_at >= CAST(:dateFrom AS timestamptz))
        AND (CAST(:dateTo AS timestamptz) IS NULL OR l.created_at < CAST(:dateTo AS timestamptz))
        AND (CAST(:valueMin AS bigint) IS NULL OR l.estimated_value >= CAST(:valueMin AS bigint))
        AND (CAST(:valueMax AS bigint) IS NULL OR l.estimated_value <= CAST(:valueMax AS bigint))
        AND (CAST(:assignedUserId AS text) IS NULL OR l.assigned_user_id = CAST(:assignedUserId AS uuid))
        AND (:serviceIds IS NULL OR lst.service_id IN (:serviceIds))
    """,
    nativeQuery = true)
    fun findByStudioIdWithExtendedFilters(
        @Param("studioId") studioId: UUID,
        @Param("statuses") statuses: List<String>?,
        @Param("sources") sources: List<String>?,
        @Param("search") search: String?,
        @Param("dateFrom") dateFrom: Instant?,
        @Param("dateTo") dateTo: Instant?,
        @Param("valueMin") valueMin: Long?,
        @Param("valueMax") valueMax: Long?,
        @Param("assignedUserId") assignedUserId: String?,
        @Param("serviceIds") serviceIds: List<UUID>?,
        pageable: Pageable
    ): Page<LeadEntity>

    /**
     * Find NEW leads without our response for longer than the threshold, alert not yet sent
     */
    @Query(value = """
        SELECT * FROM leads l
        WHERE l.studio_id = CAST(:studioId AS uuid)
        AND l.status = 'NEW'
        AND l.updated_at < CAST(:threshold AS timestamptz)
        AND l.stagnant_alert_sent_at IS NULL
    """, nativeQuery = true)
    fun findStagnantNewLeads(
        @Param("studioId") studioId: UUID,
        @Param("threshold") threshold: Instant
    ): List<LeadEntity>

    /**
     * Find IN_PROGRESS leads without client response for longer than the threshold, alert not yet sent
     */
    @Query(value = """
        SELECT * FROM leads l
        WHERE l.studio_id = CAST(:studioId AS uuid)
        AND l.status = 'IN_PROGRESS'
        AND l.updated_at < CAST(:threshold AS timestamptz)
        AND l.stagnant_alert_sent_at IS NULL
    """, nativeQuery = true)
    fun findStagnantInProgressLeads(
        @Param("studioId") studioId: UUID,
        @Param("threshold") threshold: Instant
    ): List<LeadEntity>

    /**
     * Get distinct studio IDs that have any leads (for scheduler iteration)
     */
    @Query(value = "SELECT DISTINCT l.studio_id FROM leads l", nativeQuery = true)
    fun findDistinctStudioIds(): List<UUID>

    /**
     * Time analytics — incoming leads grouped by hour or day-of-month.
     * bucket_type: 'hour' | 'day'
     */
    @Query(value = """
        SELECT EXTRACT(:#{#bucketType} FROM created_at AT TIME ZONE :timezone)::int AS bucket,
               COUNT(*) AS cnt
        FROM leads
        WHERE studio_id = :studioId
          AND (:valueMin::bigint IS NULL OR estimated_value >= :valueMin)
          AND (:valueMax::bigint IS NULL OR estimated_value <= :valueMax)
          AND (:dateFrom::date IS NULL OR created_at >= :dateFrom::date)
          AND (:dateTo::date IS NULL OR created_at < (:dateTo::date + INTERVAL '1 day'))
        GROUP BY bucket
        ORDER BY bucket
    """, nativeQuery = true)
    fun countIncomingByTimeBucket(
        @Param("studioId") studioId: UUID,
        @Param("bucketType") bucketType: String,
        @Param("timezone") timezone: String,
        @Param("valueMin") valueMin: Long?,
        @Param("valueMax") valueMax: Long?,
        @Param("dateFrom") dateFrom: String?,
        @Param("dateTo") dateTo: String?
    ): List<Array<Any>>

    /**
     * Time analytics — accepted leads (CONFIRMED/COMPLETED) by hour or day-of-month of updated_at.
     */
    @Query(value = """
        SELECT EXTRACT(:#{#bucketType} FROM updated_at AT TIME ZONE :timezone)::int AS bucket,
               COUNT(*) AS cnt
        FROM leads
        WHERE studio_id = :studioId
          AND status IN ('CONFIRMED', 'COMPLETED')
          AND (:valueMin::bigint IS NULL OR estimated_value >= :valueMin)
          AND (:valueMax::bigint IS NULL OR estimated_value <= :valueMax)
          AND (:dateFrom::date IS NULL OR updated_at >= :dateFrom::date)
          AND (:dateTo::date IS NULL OR updated_at < (:dateTo::date + INTERVAL '1 day'))
        GROUP BY bucket
        ORDER BY bucket
    """, nativeQuery = true)
    fun countAcceptedByTimeBucket(
        @Param("studioId") studioId: UUID,
        @Param("bucketType") bucketType: String,
        @Param("timezone") timezone: String,
        @Param("valueMin") valueMin: Long?,
        @Param("valueMax") valueMax: Long?,
        @Param("dateFrom") dateFrom: String?,
        @Param("dateTo") dateTo: String?
    ): List<Array<Any>>

    /**
     * Time analytics — rejected leads (LOST/NO_SHOW) by hour or day-of-month of updated_at.
     */
    @Query(value = """
        SELECT EXTRACT(:#{#bucketType} FROM updated_at AT TIME ZONE :timezone)::int AS bucket,
               COUNT(*) AS cnt
        FROM leads
        WHERE studio_id = :studioId
          AND status IN ('LOST', 'NO_SHOW')
          AND (:valueMin::bigint IS NULL OR estimated_value >= :valueMin)
          AND (:valueMax::bigint IS NULL OR estimated_value <= :valueMax)
          AND (:dateFrom::date IS NULL OR updated_at >= :dateFrom::date)
          AND (:dateTo::date IS NULL OR updated_at < (:dateTo::date + INTERVAL '1 day'))
        GROUP BY bucket
        ORDER BY bucket
    """, nativeQuery = true)
    fun countRejectedByTimeBucket(
        @Param("studioId") studioId: UUID,
        @Param("bucketType") bucketType: String,
        @Param("timezone") timezone: String,
        @Param("valueMin") valueMin: Long?,
        @Param("valueMax") valueMax: Long?,
        @Param("dateFrom") dateFrom: String?,
        @Param("dateTo") dateTo: String?
    ): List<Array<Any>>
}
