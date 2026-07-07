package pl.detailing.crm.appointment.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
interface AppointmentRepository : JpaRepository<AppointmentEntity, UUID> {

    @Query("SELECT a FROM AppointmentEntity a WHERE a.id = :id AND a.studioId = :studioId AND a.deletedAt IS NULL")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): AppointmentEntity?

    @Query("SELECT a FROM AppointmentEntity a WHERE a.id = :id AND a.studioId = :studioId")
    fun findByIdAndStudioIdIncludingDeleted(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): AppointmentEntity?

    @Query("SELECT a FROM AppointmentEntity a WHERE a.studioId = :studioId AND a.deletedAt IS NULL")
    fun findByStudioId(@Param("studioId") studioId: UUID): List<AppointmentEntity>

    @Query("""
        SELECT a FROM AppointmentEntity a
        WHERE a.studioId = :studioId
        AND a.deletedAt IS NULL
        AND a.status <> 'CANCELLED'
        AND a.endDateTime >= :startDateTime
        AND a.startDateTime <= :endDateTime
    """)
    fun findOverlappingAppointments(
        @Param("studioId") studioId: UUID,
        @Param("startDateTime") startDateTime: Instant,
        @Param("endDateTime") endDateTime: Instant
    ): List<AppointmentEntity>

    @Query("""
        SELECT a FROM AppointmentEntity a
        WHERE a.studioId = :studioId
        AND a.customerId = :customerId
        AND a.deletedAt IS NULL
        ORDER BY a.startDateTime DESC
    """)
    fun findByStudioIdAndCustomerId(
        @Param("studioId") studioId: UUID,
        @Param("customerId") customerId: UUID
    ): List<AppointmentEntity>

    @Query("""
        SELECT a FROM AppointmentEntity a
        WHERE a.studioId = :studioId
        AND a.vehicleId = :vehicleId
        AND a.deletedAt IS NULL
        ORDER BY a.startDateTime DESC
    """)
    fun findByStudioIdAndVehicleId(
        @Param("studioId") studioId: UUID,
        @Param("vehicleId") vehicleId: UUID
    ): List<AppointmentEntity>

    /**
     * Find appointments with filtering and pagination (database-side), without date filter.
     */
    @Query("""
        SELECT DISTINCT a FROM AppointmentEntity a
        LEFT JOIN CustomerEntity c ON a.customerId = c.id
        LEFT JOIN VehicleEntity v ON a.vehicleId = v.id
        WHERE a.studioId = :studioId
        AND a.deletedAt IS NULL
        AND (:customerId IS NULL OR a.customerId = :customerId)
        AND (:status IS NULL OR a.status = :status)
        AND (:searchTerm IS NULL OR :searchTerm = '' OR
             (:includePiiSearch = TRUE AND (
                 LOWER(c.firstName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
                 LOWER(c.lastName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
                 LOWER(c.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
                 LOWER(c.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%')))) OR
             LOWER(v.licensePlate) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(v.brand) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(v.model) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(a.appointmentTitle) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
        ORDER BY a.startDateTime DESC
    """)
    fun findAppointmentsWithFilters(
        @Param("studioId") studioId: UUID,
        @Param("customerId") customerId: UUID?,
        @Param("status") status: pl.detailing.crm.appointment.domain.AppointmentStatus?,
        @Param("searchTerm") searchTerm: String?,
        @Param("includePiiSearch") includePiiSearch: Boolean,
        pageable: Pageable
    ): Page<AppointmentEntity>

    /**
     * Find appointments with filtering, pagination and timezone-aware date filter.
     * startOfDay/endOfDay are UTC Instants representing midnight in the target timezone.
     */
    @Query("""
        SELECT DISTINCT a FROM AppointmentEntity a
        LEFT JOIN CustomerEntity c ON a.customerId = c.id
        LEFT JOIN VehicleEntity v ON a.vehicleId = v.id
        WHERE a.studioId = :studioId
        AND a.deletedAt IS NULL
        AND (:customerId IS NULL OR a.customerId = :customerId)
        AND (:status IS NULL OR a.status = :status)
        AND (:searchTerm IS NULL OR :searchTerm = '' OR
             (:includePiiSearch = TRUE AND (
                 LOWER(c.firstName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
                 LOWER(c.lastName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
                 LOWER(c.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
                 LOWER(c.phone) LIKE LOWER(CONCAT('%', :searchTerm, '%')))) OR
             LOWER(v.licensePlate) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(v.brand) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(v.model) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR
             LOWER(a.appointmentTitle) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
        AND a.startDateTime >= :startOfDay
        AND a.startDateTime < :endOfDay
        ORDER BY a.startDateTime DESC
    """)
    fun findAppointmentsWithFiltersAndScheduledDate(
        @Param("studioId") studioId: UUID,
        @Param("customerId") customerId: UUID?,
        @Param("status") status: pl.detailing.crm.appointment.domain.AppointmentStatus?,
        @Param("searchTerm") searchTerm: String?,
        @Param("includePiiSearch") includePiiSearch: Boolean,
        @Param("startOfDay") startOfDay: Instant,
        @Param("endOfDay") endOfDay: Instant,
        pageable: Pageable
    ): Page<AppointmentEntity>

    /**
     * Find appointments by studio, status and scheduled date range (timezone-aware).
     * startOfDay and endOfDay should be the UTC Instants corresponding to midnight in the target timezone.
     */
    @Query("""
        SELECT a FROM AppointmentEntity a
        WHERE a.studioId = :studioId
        AND a.deletedAt IS NULL
        AND a.status = :status
        AND a.startDateTime >= :startOfDay
        AND a.startDateTime < :endOfDay
        ORDER BY a.startDateTime ASC
    """)
    fun findByStudioIdAndStatusAndDateRange(
        @Param("studioId") studioId: UUID,
        @Param("status") status: pl.detailing.crm.appointment.domain.AppointmentStatus,
        @Param("startOfDay") startOfDay: Instant,
        @Param("endOfDay") endOfDay: Instant
    ): List<AppointmentEntity>

    /**
     * Find appointments that should be marked as abandoned:
     * - Status is CREATED (not yet converted to a visit, cancelled, or already abandoned)
     * - Start date is yesterday or earlier (startDateTime < startOfToday in Europe/Warsaw, stored as UTC)
     * - Not soft-deleted
     */
    @Query("""
        SELECT a FROM AppointmentEntity a
        WHERE a.deletedAt IS NULL
        AND a.status = 'CREATED'
        AND a.startDateTime < :startOfToday
    """)
    fun findAbandonedCandidates(@Param("startOfToday") startOfToday: Instant): List<AppointmentEntity>

    /**
     * Count appointments marked as ABANDONED within a date range for a specific studio.
     * Uses updatedAt to reflect when the appointment was actually marked as abandoned.
     */
    @Query("""
        SELECT COUNT(a) FROM AppointmentEntity a
        WHERE a.studioId = :studioId
        AND a.deletedAt IS NULL
        AND a.status IN ('ABANDONED', 'CANCELLED')
        AND a.updatedAt >= :startDate
        AND a.updatedAt < :endDate
    """)
    fun countAbandonedByStudioIdAndDateRange(
        @Param("studioId") studioId: UUID,
        @Param("startDate") startDate: Instant,
        @Param("endDate") endDate: Instant
    ): Long

    /**
     * Find appointments marked as ABANDONED within a date range for a specific studio.
     * Uses updatedAt to reflect when the appointment was actually marked as abandoned.
     */
    @Query("""
        SELECT a FROM AppointmentEntity a
        WHERE a.studioId = :studioId
        AND a.deletedAt IS NULL
        AND a.status IN ('ABANDONED', 'CANCELLED')
        AND a.updatedAt >= :startDate
        AND a.updatedAt < :endDate
        ORDER BY a.updatedAt DESC
    """)
    fun findAbandonedByStudioIdAndDateRange(
        @Param("studioId") studioId: UUID,
        @Param("startDate") startDate: Instant,
        @Param("endDate") endDate: Instant
    ): List<AppointmentEntity>

    /**
     * Find appointments for the unified calendar view using intersection predicate.
     * Returns appointments whose time window overlaps with [startDate, endDate].
     */
    @Query("""
        SELECT a FROM AppointmentEntity a
        WHERE a.studioId = :studioId
        AND a.deletedAt IS NULL
        AND a.status IN :statuses
        AND a.startDateTime < :endDate
        AND a.endDateTime > :startDate
        AND (:customerId IS NULL OR a.customerId = :customerId)
        AND (:vehicleId IS NULL OR a.vehicleId = :vehicleId)
        ORDER BY a.startDateTime ASC
    """)
    fun findForCalendar(
        @Param("studioId") studioId: UUID,
        @Param("statuses") statuses: List<pl.detailing.crm.appointment.domain.AppointmentStatus>,
        @Param("startDate") startDate: Instant,
        @Param("endDate") endDate: Instant,
        @Param("customerId") customerId: UUID?,
        @Param("vehicleId") vehicleId: UUID?
    ): List<AppointmentEntity>

    @Query("""
        SELECT a FROM AppointmentEntity a
        WHERE a.recurrenceSeriesId = :seriesId
        AND a.deletedAt IS NULL
        ORDER BY a.recurrenceIndex ASC
    """)
    fun findBySeriesId(@Param("seriesId") seriesId: java.util.UUID): List<AppointmentEntity>

    @Query("""
        SELECT a FROM AppointmentEntity a
        WHERE a.recurrenceSeriesId = :seriesId
        AND a.recurrenceIndex >= :fromIndex
        AND a.isDetached = false
        AND a.deletedAt IS NULL
        ORDER BY a.recurrenceIndex ASC
    """)
    fun findBySeriesIdAndIndexGreaterThanEqual(
        @Param("seriesId") seriesId: java.util.UUID,
        @Param("fromIndex") fromIndex: Int
    ): List<AppointmentEntity>

    @Query("""
        SELECT a FROM AppointmentEntity a
        WHERE a.recurrenceSeriesId = :seriesId
        AND a.isDetached = false
        AND a.deletedAt IS NULL
        ORDER BY a.recurrenceIndex ASC
    """)
    fun findNonDetachedBySeriesId(@Param("seriesId") seriesId: java.util.UUID): List<AppointmentEntity>

    @Query("SELECT COUNT(a) FROM AppointmentEntity a WHERE a.recurrenceSeriesId = :seriesId AND a.deletedAt IS NULL")
    fun countBySeriesId(@Param("seriesId") seriesId: java.util.UUID): Long

    /**
     * Find all non-deleted appointments for a studio created within [from, to).
     * Used for reservation intake tracking (grouped by createdAt week).
     */
    @Query("""
        SELECT a FROM AppointmentEntity a
        WHERE a.studioId = :studioId
        AND a.deletedAt IS NULL
        AND a.createdAt >= :from
        AND a.createdAt < :to
    """)
    fun findByStudioIdAndCreatedAtRange(
        @Param("studioId") studioId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant
    ): List<AppointmentEntity>
}
