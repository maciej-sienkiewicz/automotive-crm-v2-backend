package pl.detailing.crm.visit.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.*

@Repository
interface VisitRepository : JpaRepository<VisitEntity, UUID> {

    /**
     * Find visit by ID with studio isolation
     */
    @Query("SELECT v FROM VisitEntity v WHERE v.id = :id AND v.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): VisitEntity?

    /**
     * Find visit by ID with studio isolation, eagerly fetching photos
     */
    @Query("SELECT v FROM VisitEntity v LEFT JOIN FETCH v.photos WHERE v.id = :id AND v.studioId = :studioId")
    fun findByIdAndStudioIdWithPhotos(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): VisitEntity?

    /**
     * Find all visits for a studio
     */
    @Query("SELECT v FROM VisitEntity v WHERE v.studioId = :studioId ORDER BY v.scheduledDate DESC")
    fun findByStudioId(@Param("studioId") studioId: UUID): List<VisitEntity>

    /**
     * Find visits by studio and status
     */
    @Query("SELECT v FROM VisitEntity v WHERE v.studioId = :studioId AND v.status = :status ORDER BY v.scheduledDate DESC")
    fun findByStudioIdAndStatus(
        @Param("studioId") studioId: UUID,
        @Param("status") status: pl.detailing.crm.shared.VisitStatus
    ): List<VisitEntity>

    /**
     * Find visits by customer with studio isolation
     */
    @Query("SELECT v FROM VisitEntity v WHERE v.customerId = :customerId AND v.studioId = :studioId ORDER BY v.scheduledDate DESC")
    fun findByCustomerIdAndStudioId(
        @Param("customerId") customerId: UUID,
        @Param("studioId") studioId: UUID
    ): List<VisitEntity>

    /**
     * Find visits by customer with studio isolation, excluding DRAFT status
     */
    @Query("SELECT v FROM VisitEntity v WHERE v.customerId = :customerId AND v.studioId = :studioId AND v.status != pl.detailing.crm.shared.VisitStatus.DRAFT ORDER BY v.scheduledDate DESC")
    fun findByCustomerIdAndStudioIdExcludingDraft(
        @Param("customerId") customerId: UUID,
        @Param("studioId") studioId: UUID
    ): List<VisitEntity>

    /**
     * Find visits by vehicle with studio isolation, excluding DRAFT status
     */
    @Query("SELECT v FROM VisitEntity v WHERE v.vehicleId = :vehicleId AND v.studioId = :studioId AND v.status != pl.detailing.crm.shared.VisitStatus.DRAFT ORDER BY v.scheduledDate DESC")
    fun findByVehicleIdAndStudioIdExcludingDraft(
        @Param("vehicleId") vehicleId: UUID,
        @Param("studioId") studioId: UUID
    ): List<VisitEntity>

    /**
     * Check if visit number already exists for studio
     */
    @Query("SELECT COUNT(v) > 0 FROM VisitEntity v WHERE v.visitNumber = :visitNumber AND v.studioId = :studioId")
    fun existsByVisitNumberAndStudioId(
        @Param("visitNumber") visitNumber: String,
        @Param("studioId") studioId: UUID
    ): Boolean

    /**
     * Find visit by appointment ID with studio isolation
     */
    @Query("SELECT v FROM VisitEntity v WHERE v.appointmentId = :appointmentId AND v.studioId = :studioId")
    fun findByAppointmentIdAndStudioId(
        @Param("appointmentId") appointmentId: UUID,
        @Param("studioId") studioId: UUID
    ): VisitEntity?

    /**
     * Get next visit number sequence for studio
     * Format: VIS-YYYY-NNNNN
     */
    @Query("""
        SELECT v.visitNumber FROM VisitEntity v
        WHERE v.studioId = :studioId
        AND v.visitNumber LIKE :yearPattern
        ORDER BY v.visitNumber DESC
    """)
    fun findLatestVisitNumberForYear(
        @Param("studioId") studioId: UUID,
        @Param("yearPattern") yearPattern: String
    ): List<String>

    /**
     * Find visits with filtering and pagination (database-side), without date filter.
     */
    @Query("""
        SELECT DISTINCT v FROM VisitEntity v
        LEFT JOIN CustomerEntity c ON v.customerId = c.id
        LEFT JOIN VehicleEntity veh ON v.vehicleId = veh.id
        WHERE v.studioId = :studioId
        AND (:status IS NULL OR v.status = :status)
        AND (v.status != pl.detailing.crm.shared.VisitStatus.DRAFT)
        AND (:searchTerm IS NULL OR :searchTerm = '' OR
             LOWER(c.firstName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(c.lastName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(c.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(c.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(veh.licensePlate) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(veh.brand) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(veh.model) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(v.brandSnapshot) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(v.modelSnapshot) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(v.licensePlateSnapshot) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
        ORDER BY v.scheduledDate DESC
    """)
    fun findVisitsWithFilters(
        @Param("studioId") studioId: UUID,
        @Param("status") status: pl.detailing.crm.shared.VisitStatus?,
        @Param("searchTerm") searchTerm: String?,
        pageable: Pageable
    ): Page<VisitEntity>

    /**
     * Find visits with filtering, pagination and timezone-aware date filter.
     * startOfDay/endOfDay are UTC Instants representing midnight in the target timezone.
     */
    @Query("""
        SELECT DISTINCT v FROM VisitEntity v
        LEFT JOIN CustomerEntity c ON v.customerId = c.id
        LEFT JOIN VehicleEntity veh ON v.vehicleId = veh.id
        WHERE v.studioId = :studioId
        AND (:status IS NULL OR v.status = :status)
        AND (v.status != pl.detailing.crm.shared.VisitStatus.DRAFT)
        AND (:searchTerm IS NULL OR :searchTerm = '' OR
             LOWER(c.firstName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(c.lastName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(c.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(c.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(veh.licensePlate) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(veh.brand) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(veh.model) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(v.brandSnapshot) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(v.modelSnapshot) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(v.licensePlateSnapshot) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
        AND v.scheduledDate >= :startOfDay
        AND v.scheduledDate < :endOfDay
        ORDER BY v.scheduledDate DESC
    """)
    fun findVisitsWithFiltersAndScheduledDate(
        @Param("studioId") studioId: UUID,
        @Param("status") status: pl.detailing.crm.shared.VisitStatus?,
        @Param("searchTerm") searchTerm: String?,
        @Param("startOfDay") startOfDay: Instant,
        @Param("endOfDay") endOfDay: Instant,
        pageable: Pageable
    ): Page<VisitEntity>

    /**
     * Find all non-draft visits for a studio that have at least one photo, eagerly fetching photos.
     * Used by the gallery endpoint to aggregate all visit photos.
     */
    @Query("""
        SELECT DISTINCT v FROM VisitEntity v
        JOIN FETCH v.photos
        WHERE v.studioId = :studioId
        AND v.status != pl.detailing.crm.shared.VisitStatus.DRAFT
    """)
    fun findByStudioIdWithPhotos(@Param("studioId") studioId: UUID): List<VisitEntity>

    @Query("""
        SELECT DISTINCT v.customerId FROM VisitEntity v
        JOIN v.serviceItems si
        WHERE v.studioId = :studioId
        AND si.serviceId IN :serviceIds
    """)
    fun findCustomerIdsByServiceIds(
        @Param("studioId") studioId: UUID,
        @Param("serviceIds") serviceIds: List<UUID>
    ): List<UUID>

    /**
     * Find visits for the unified calendar view.
     * Uses COALESCE so multi-day visits (with estimatedCompletionDate) are included
     * when they overlap the range, and single-date visits are included when their
     * scheduledDate falls within the range.
     * Only serviceItems are JOIN FETCHed (needed for total calculation); photos are
     * intentionally omitted — the calendar response does not include them, and
     * fetching two bag collections simultaneously causes MultipleBagFetchException.
     */
    @Query("""
        SELECT DISTINCT v FROM VisitEntity v
        LEFT JOIN FETCH v.serviceItems
        WHERE v.studioId = :studioId
        AND v.status IN :statuses
        AND v.scheduledDate < :endDate
        AND COALESCE(v.estimatedCompletionDate, v.scheduledDate) >= :startDate
        ORDER BY v.scheduledDate ASC
    """)
    fun findForCalendar(
        @Param("studioId") studioId: UUID,
        @Param("statuses") statuses: List<pl.detailing.crm.shared.VisitStatus>,
        @Param("startDate") startDate: Instant,
        @Param("endDate") endDate: Instant
    ): List<VisitEntity>

    /**
     * Calculate total revenue for visits within a date range
     * Uses service items' finalPriceGross for historical accuracy
     */
    @Query("""
        SELECT COALESCE(SUM(vsi.finalPriceGross), 0)
        FROM VisitEntity v
        JOIN v.serviceItems vsi
        WHERE v.studioId = :studioId
        AND v.createdAt >= :startDate
        AND v.createdAt < :endDate
        AND v.status != 'REJECTED'
        AND v.status != 'ARCHIVED'
    """)
    fun sumRevenueByStudioIdAndDateRange(
        @Param("studioId") studioId: UUID,
        @Param("startDate") startDate: java.time.Instant,
        @Param("endDate") endDate: java.time.Instant
    ): Long
}

@Repository
interface VisitServiceItemRepository : JpaRepository<VisitServiceItemEntity, UUID> {

    /**
     * Find all service items for a visit
     */
    @Query("SELECT vsi FROM VisitServiceItemEntity vsi WHERE vsi.visit.id = :visitId")
    fun findByVisitId(@Param("visitId") visitId: UUID): List<VisitServiceItemEntity>

    /**
     * Find service item by ID with visit validation
     */
    @Query("SELECT vsi FROM VisitServiceItemEntity vsi WHERE vsi.id = :id AND vsi.visit.id = :visitId")
    fun findByIdAndVisitId(
        @Param("id") id: UUID,
        @Param("visitId") visitId: UUID
    ): VisitServiceItemEntity?
}

@Repository
interface VisitJournalEntryRepository : JpaRepository<VisitJournalEntryEntity, UUID> {

    /**
     * Find all journal entries for a visit (excluding deleted)
     */
    @Query("""
        SELECT vje FROM VisitJournalEntryEntity vje
        WHERE vje.visit.id = :visitId AND vje.isDeleted = false
        ORDER BY vje.createdAt ASC
    """)
    fun findByVisitId(@Param("visitId") visitId: UUID): List<VisitJournalEntryEntity>

    /**
     * Find journal entry by ID with visit validation
     */
    @Query("SELECT vje FROM VisitJournalEntryEntity vje WHERE vje.id = :id AND vje.visit.id = :visitId")
    fun findByIdAndVisitId(
        @Param("id") id: UUID,
        @Param("visitId") visitId: UUID
    ): VisitJournalEntryEntity?
}

@Repository
interface VisitDocumentRepository : JpaRepository<VisitDocumentEntity, UUID> {

    /**
     * Find all documents for a visit
     * Ordered by upload date descending (newest first)
     */
    @Query("SELECT vd FROM VisitDocumentEntity vd WHERE vd.visit.id = :visitId ORDER BY vd.uploadedAt DESC")
    fun findByVisitId(@Param("visitId") visitId: UUID): List<VisitDocumentEntity>

    /**
     * Find all documents for a specific visit
     * Ordered by upload date descending (newest first)
     */
    fun findByVisit_IdOrderByUploadedAtDesc(visitId: UUID): List<VisitDocumentEntity>

    /**
     * Find all documents for a specific customer across all visits
     * Ordered by upload date descending (newest first)
     * This uses the denormalized customer_id for fast historical lookups
     */
    fun findByCustomerIdOrderByUploadedAtDesc(customerId: UUID): List<VisitDocumentEntity>

    /**
     * Find all documents for a specific visit and customer
     */
    fun findByVisit_IdAndCustomerId(visitId: UUID, customerId: UUID): List<VisitDocumentEntity>

    /**
     * Find document by ID with visit validation
     */
    @Query("SELECT vd FROM VisitDocumentEntity vd WHERE vd.id = :id AND vd.visit.id = :visitId")
    fun findByIdAndVisitId(
        @Param("id") id: UUID,
        @Param("visitId") visitId: UUID
    ): VisitDocumentEntity?

    /**
     * Check if a document exists for a visit
     */
    fun existsByVisit_Id(visitId: UUID): Boolean

    /**
     * Delete all documents for a specific visit
     * Note: This should be called before visit deletion
     */
    fun deleteByVisit_Id(visitId: UUID)
}
