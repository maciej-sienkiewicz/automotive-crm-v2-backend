package pl.detailing.crm.communication.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CommunicationStatus
import java.util.UUID

@Repository
interface CommunicationLogJpaRepository : JpaRepository<CommunicationLogEntity, UUID> {

    /** Wpisy założone przy odłożeniu wiadomości do kolejki wysyłkowej (zwykle jeden). */
    fun findAllByQueuedMessageId(queuedMessageId: UUID): List<CommunicationLogEntity>

    /**
     * Domknięcie wpisu QUEUED, gdy dispatcher kolejki faktycznie spróbował wysłać.
     * Encja jest niemutowalna z założenia (wpis to fakt), więc jedyna dozwolona zmiana —
     * z „czeka" na „wyszło / nie wyszło" — idzie jednym UPDATE-em, bez kopiowania wiersza.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE CommunicationLogEntity c
        SET c.status = :status, c.errorMessage = :errorMessage, c.sentAt = :sentAt
        WHERE c.queuedMessageId = :queuedMessageId AND c.status = :from
    """)
    fun resolveQueued(
        @Param("queuedMessageId") queuedMessageId: UUID,
        @Param("from") from: CommunicationStatus,
        @Param("status") status: CommunicationStatus,
        @Param("errorMessage") errorMessage: String?,
        @Param("sentAt") sentAt: java.time.Instant
    ): Int

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
    @Modifying(clearAutomatically = true)
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

    /**
     * Successful sends of the given message types for a visit and/or the
     * reservation it originated from, newest-first. Used by the Visit Card
     * link modal to tell the employee the card has already been delivered.
     * Pass null for the id you don't have — a null id matches nothing.
     */
    @Query("""
        SELECT c FROM CommunicationLogEntity c
        WHERE c.studioId = :studioId
          AND c.status = :status
          AND c.messageType IN :messageTypes
          AND (c.visitId = :visitId OR c.appointmentId = :appointmentId)
        ORDER BY c.sentAt DESC
    """)
    fun findSentByTypesForVisitOrAppointment(
        @Param("studioId") studioId: UUID,
        @Param("visitId") visitId: UUID?,
        @Param("appointmentId") appointmentId: UUID?,
        @Param("messageTypes") messageTypes: Collection<CommunicationMessageType>,
        @Param("status") status: CommunicationStatus
    ): List<CommunicationLogEntity>
}
