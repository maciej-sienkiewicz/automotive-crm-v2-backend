package pl.detailing.crm.visit.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
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
     * Find visits by vehicle with studio isolation
     */
    @Query("SELECT v FROM VisitEntity v WHERE v.vehicleId = :vehicleId AND v.studioId = :studioId ORDER BY v.scheduledDate DESC")
    fun findByVehicleIdAndStudioId(
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
