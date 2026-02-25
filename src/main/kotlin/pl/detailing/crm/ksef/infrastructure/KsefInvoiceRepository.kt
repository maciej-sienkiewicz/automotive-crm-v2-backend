package pl.detailing.crm.ksef.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface KsefInvoiceRepository : JpaRepository<KsefInvoiceEntity, UUID> {
    fun findAllByStudioId(studioId: UUID, pageable: Pageable): Page<KsefInvoiceEntity>
    fun existsByStudioIdAndKsefNumber(studioId: UUID, ksefNumber: String): Boolean
    fun countByStudioId(studioId: UUID): Long
}
