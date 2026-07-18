package pl.detailing.crm.ksef.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface KsefInvoiceItemRepository : JpaRepository<KsefInvoiceItemEntity, UUID> {

    fun findByInvoiceIdOrderByLineNumberAsc(invoiceId: UUID): List<KsefInvoiceItemEntity>

    /** Bulk load items for multiple invoices — used when applying auto-assignment rules. */
    fun findByInvoiceIdIn(invoiceIds: Collection<UUID>): List<KsefInvoiceItemEntity>

    fun existsByInvoiceId(invoiceId: UUID): Boolean

    @Modifying
    fun deleteByInvoiceId(invoiceId: UUID): Int
}
