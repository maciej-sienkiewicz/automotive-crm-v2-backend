package pl.detailing.crm.communication.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
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
}
