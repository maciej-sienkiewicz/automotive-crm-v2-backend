package pl.detailing.crm.ksef.revenue.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface KsefRevenueInvoiceItemRepository : JpaRepository<KsefRevenueInvoiceItemEntity, UUID> {

    fun findByInvoiceIdOrderByLineNumberAsc(invoiceId: UUID): List<KsefRevenueInvoiceItemEntity>

    fun deleteByInvoiceId(invoiceId: UUID)
}
