package pl.detailing.crm.communication.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CommunicationLogJpaRepository : JpaRepository<CommunicationLogEntity, UUID> {

    /**
     * All communication entries for a specific visit, ordered newest-first.
     * Used by the visit detail view.
     */
    @Query("""
        SELECT c FROM CommunicationLogEntity c
        WHERE c.visitId = :visitId
          AND c.studioId = :studioId
        ORDER BY c.sentAt DESC
    """)
    fun findByVisitIdAndStudioId(
        @Param("visitId") visitId: UUID,
        @Param("studioId") studioId: UUID
    ): List<CommunicationLogEntity>

    /**
     * Full communication history for a customer across all visits, ordered newest-first.
     * Used by the customer profile view.
     */
    @Query("""
        SELECT c FROM CommunicationLogEntity c
        WHERE c.customerId = :customerId
          AND c.studioId = :studioId
        ORDER BY c.sentAt DESC
    """)
    fun findByCustomerIdAndStudioId(
        @Param("customerId") customerId: UUID,
        @Param("studioId") studioId: UUID
    ): List<CommunicationLogEntity>

    /**
     * Retroactively assigns [visitId] to all communication entries that were recorded for
     * [appointmentId] but had no visit yet (visitId IS NULL).
     *
     * Called once, immediately after a reservation is converted to a visit, so that
     * booking-confirmation and pre-visit reminder SMS appear in the visit's communication log.
     *
     * Returns the number of rows updated.
     */
    @Modifying
    @Query("""
        UPDATE CommunicationLogEntity c
        SET c.visitId = :visitId
        WHERE c.appointmentId = :appointmentId
          AND c.studioId = :studioId
          AND c.visitId IS NULL
    """)
    fun linkAppointmentCommunicationToVisit(
        @Param("appointmentId") appointmentId: UUID,
        @Param("visitId") visitId: UUID,
        @Param("studioId") studioId: UUID
    ): Int
}
