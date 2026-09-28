package pl.detailing.crm.finance.external

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ExternalInvoiceRequestRepository : JpaRepository<ExternalInvoiceRequestEntity, UUID> {

    fun findByIdAndStudioId(id: UUID, studioId: UUID): ExternalInvoiceRequestEntity?

    /** Zgłoszenia wizyty od najstarszego — także wycofane, bo to historia rozliczenia. */
    fun findByStudioIdAndVisitIdOrderByCreatedAtAsc(studioId: UUID, visitId: UUID): List<ExternalInvoiceRequestEntity>

    @Query("""
        SELECT r FROM ExternalInvoiceRequestEntity r
        WHERE r.studioId = :studioId
          AND r.financialDocumentId IN :documentIds
          AND r.status <> pl.detailing.crm.finance.external.ExternalInvoiceStatus.WITHDRAWN
        ORDER BY r.createdAt ASC
    """)
    fun findActiveByDocuments(studioId: UUID, documentIds: Collection<UUID>): List<ExternalInvoiceRequestEntity>

    @Query("""
        SELECT r FROM ExternalInvoiceRequestEntity r
        WHERE r.studioId = :studioId
          AND r.status IN :statuses
        ORDER BY r.createdAt DESC
    """)
    fun findPage(studioId: UUID, statuses: Collection<ExternalInvoiceStatus>, pageable: Pageable): Page<ExternalInvoiceRequestEntity>

    @Query("""
        SELECT r FROM ExternalInvoiceRequestEntity r
        WHERE r.studioId = :studioId
          AND r.financialDocumentId = :documentId
          AND r.status = pl.detailing.crm.finance.external.ExternalInvoiceStatus.WITHDRAWN
    """)
    fun findWithdrawnByDocument(studioId: UUID, documentId: UUID): List<ExternalInvoiceRequestEntity>

    fun countByStudioIdAndStatus(studioId: UUID, status: ExternalInvoiceStatus): Long
}
