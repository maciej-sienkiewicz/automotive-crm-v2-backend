package pl.detailing.crm.invoicing.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import pl.detailing.crm.invoicing.domain.InvoiceProviderType
import java.util.UUID

interface ExternalInvoiceRepository : JpaRepository<ExternalInvoiceEntity, UUID> {

    fun findByStudioIdAndId(studioId: UUID, id: UUID): ExternalInvoiceEntity?

    fun findByStudioIdAndProviderAndExternalId(
        studioId: UUID,
        provider: InvoiceProviderType,
        externalId: String
    ): ExternalInvoiceEntity?

    fun findByStudioIdAndProvider(
        studioId: UUID,
        provider: InvoiceProviderType,
        pageable: Pageable
    ): Page<ExternalInvoiceEntity>

    /** Used by background sync to find all invoices for a studio that are not in a terminal state. */
    @Query("""
        SELECT e FROM ExternalInvoiceEntity e
        WHERE e.studioId = :studioId
          AND e.provider = :provider
          AND e.status NOT IN (
              pl.detailing.crm.invoicing.domain.ExternalInvoiceStatus.PAID,
              pl.detailing.crm.invoicing.domain.ExternalInvoiceStatus.CANCELLED
          )
        ORDER BY e.issueDate DESC
    """)
    fun findActiveByStudioIdAndProvider(
        studioId: UUID,
        provider: InvoiceProviderType
    ): List<ExternalInvoiceEntity>

    fun countByStudioIdAndProvider(studioId: UUID, provider: InvoiceProviderType): Long
}
