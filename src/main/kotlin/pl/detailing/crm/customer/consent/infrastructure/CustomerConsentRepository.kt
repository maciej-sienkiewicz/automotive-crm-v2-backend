package pl.detailing.crm.customer.consent.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for CustomerConsent entities.
 * All queries are filtered by studioId for multi-tenancy.
 *
 * Remember: CustomerConsent is append-only. Never update or delete records.
 */
@Repository
interface CustomerConsentRepository : JpaRepository<CustomerConsentEntity, UUID> {

    @Query("""
        SELECT cc FROM CustomerConsentEntity cc
        WHERE cc.customerId = :customerId
        AND cc.studioId = :studioId
    """)
    fun findAllByCustomerIdAndStudioId(
        @Param("customerId") customerId: UUID,
        @Param("studioId") studioId: UUID
    ): List<CustomerConsentEntity>

    @Query("""
        SELECT cc FROM CustomerConsentEntity cc
        WHERE cc.customerId = :customerId
        AND cc.templateId = :templateId
        AND cc.studioId = :studioId
        ORDER BY cc.signedAt DESC
        LIMIT 1
    """)
    fun findLatestByCustomerAndTemplate(
        @Param("customerId") customerId: UUID,
        @Param("templateId") templateId: UUID,
        @Param("studioId") studioId: UUID
    ): CustomerConsentEntity?

    @Query("""
        SELECT CASE WHEN COUNT(cc) > 0 THEN true ELSE false END
        FROM CustomerConsentEntity cc
        WHERE cc.customerId = :customerId
        AND cc.templateId = :templateId
        AND cc.studioId = :studioId
    """)
    fun existsByCustomerAndTemplate(
        @Param("customerId") customerId: UUID,
        @Param("templateId") templateId: UUID,
        @Param("studioId") studioId: UUID
    ): Boolean
}
