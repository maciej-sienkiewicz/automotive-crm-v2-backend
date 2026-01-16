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
     * Find all visits for a studio
     */
    @Query("SELECT v FROM VisitEntity v WHERE v.studioId = :studioId ORDER BY v.scheduledDate DESC")
    fun findByStudioId(@Param("studioId") studioId: UUID): List<VisitEntity>

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
