package pl.detailing.crm.smscampaigns.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface SmsConsentRequestRepository : JpaRepository<SmsConsentRequestEntity, UUID> {

    /**
     * Returns the most recent PENDING consent request for the given phone number.
     * Used by the inbound-reply webhook to match an incoming "TAK" to a visit.
     */
    fun findTopByCustomerPhoneAndStatusOrderByCreatedAtDesc(
        customerPhone: String,
        status: SmsConsentRequestStatus
    ): SmsConsentRequestEntity?

    /**
     * Returns all PENDING consent requests for a visit.
     * Used to supersede old requests when a new one is created for the same visit.
     */
    fun findByVisitIdAndStatus(
        visitId: UUID,
        status: SmsConsentRequestStatus
    ): List<SmsConsentRequestEntity>

    @Modifying
    @Query("""
        UPDATE SmsConsentRequestEntity r
        SET r.status = 'SUPERSEDED'
        WHERE r.visitId = :visitId AND r.status = 'PENDING'
    """)
    fun supersedePendingByVisitId(@Param("visitId") visitId: UUID)
}
