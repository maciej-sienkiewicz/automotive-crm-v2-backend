package pl.detailing.crm.finance.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.invoicing.domain.InvoiceProviderSyncStatus
import pl.detailing.crm.invoicing.domain.InvoiceProviderType
import java.time.LocalDate
import java.util.UUID

@Repository
interface FinancialDocumentRepository : JpaRepository<FinancialDocumentEntity, UUID> {

    // ── Tenant-scoped lookups ─────────────────────────────────────────────────

    @Query("""
        SELECT d FROM FinancialDocumentEntity d
        WHERE d.id = :id AND d.studioId = :studioId AND d.deletedAt IS NULL
    """)
    fun findByIdAndStudioId(id: UUID, studioId: UUID): FinancialDocumentEntity?

    // ── Document-number generation ────────────────────────────────────────────

    /**
     * Counts existing (non-deleted) documents of a given type for a studio within
     * a calendar year, used to generate the next sequential document number.
     *
     * Uses a date-range predicate for portability across SQL dialects.
     */
    @Query("""
        SELECT COUNT(d) FROM FinancialDocumentEntity d
        WHERE d.studioId = :studioId
          AND d.documentType = :documentType
          AND d.issueDate >= :yearStart
          AND d.issueDate < :yearEnd
          AND d.deletedAt IS NULL
    """)
    fun countByStudioTypeAndYear(
        studioId: UUID,
        documentType: DocumentType,
        yearStart: LocalDate,
        yearEnd: LocalDate
    ): Long

    // ── Filterable paginated list ─────────────────────────────────────────────

    /**
     * Returns a paginated list of documents for a studio with optional filters.
     * All parameters except [studioId] are optional (pass null to skip).
     */
    @Query("""
        SELECT d FROM FinancialDocumentEntity d
        WHERE d.studioId = :studioId
          AND d.deletedAt IS NULL
          AND (:documentType IS NULL OR d.documentType = :documentType)
          AND (:direction    IS NULL OR d.direction    = :direction)
          AND (:status       IS NULL OR d.status       = :status)
          AND (:visitId      IS NULL OR d.visitId      = :visitId)
          AND (:dateFrom     IS NULL OR d.issueDate   >= :dateFrom)
          AND (:dateTo       IS NULL OR d.issueDate   <= :dateTo)
    """)
    fun findWithFilters(
        studioId: UUID,
        documentType: DocumentType?,
        direction: DocumentDirection?,
        status: DocumentStatus?,
        visitId: UUID?,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
        pageable: Pageable
    ): Page<FinancialDocumentEntity>

    // ── Reporting aggregations ────────────────────────────────────────────────

    /**
     * Returns the sum of gross amounts for documents matching the given criteria.
     * Returns null (mapped to 0 by callers) when no matching rows exist.
     */
    @Query("""
        SELECT COALESCE(SUM(d.totalGross), 0) FROM FinancialDocumentEntity d
        WHERE d.studioId  = :studioId
          AND d.direction = :direction
          AND d.status    = :status
          AND d.deletedAt IS NULL
          AND (:dateFrom IS NULL OR d.issueDate >= :dateFrom)
          AND (:dateTo   IS NULL OR d.issueDate <= :dateTo)
    """)
    fun sumGross(
        studioId: UUID,
        direction: DocumentDirection,
        status: DocumentStatus,
        dateFrom: LocalDate?,
        dateTo: LocalDate?
    ): Long

    /**
     * Counts documents in OVERDUE status, optionally filtered by direction.
     */
    @Query("""
        SELECT COUNT(d) FROM FinancialDocumentEntity d
        WHERE d.studioId  = :studioId
          AND d.status    = 'OVERDUE'
          AND d.deletedAt IS NULL
          AND (:direction IS NULL OR d.direction = :direction)
    """)
    fun countOverdue(studioId: UUID, direction: DocumentDirection?): Long

    /**
     * Returns documents that are PENDING and past their due date –
     * used by the overdue-marking job.
     */
    @Query("""
        SELECT d FROM FinancialDocumentEntity d
        WHERE d.studioId  = :studioId
          AND d.status    = 'PENDING'
          AND d.dueDate   < :today
          AND d.deletedAt IS NULL
    """)
    fun findPendingOverdue(studioId: UUID, today: LocalDate): List<FinancialDocumentEntity>

    // ── Bulk operations ───────────────────────────────────────────────────────

    @Modifying
    @Query("""
        UPDATE FinancialDocumentEntity d
        SET d.status = :newStatus, d.updatedAt = CURRENT_TIMESTAMP
        WHERE d.studioId = :studioId
          AND d.status   = 'PENDING'
          AND d.dueDate  < :today
          AND d.deletedAt IS NULL
    """)
    fun markOverdueBatch(studioId: UUID, today: LocalDate, newStatus: DocumentStatus): Int

    // ── Provider integration queries ──────────────────────────────────────────

    /** Looks up a document by its external provider ID. Used to prevent import duplicates. */
    fun findByStudioIdAndProviderAndExternalId(
        studioId: UUID,
        provider: InvoiceProviderType,
        externalId: String
    ): FinancialDocumentEntity?

    /**
     * Finds all active (non-terminal) INVOICE documents for background status sync.
     * Skips PAID/CANCELLED external statuses since they no longer change.
     */
    @Query("""
        SELECT d FROM FinancialDocumentEntity d
        WHERE d.studioId = :studioId
          AND d.provider = :provider
          AND d.externalId IS NOT NULL
          AND d.externalStatus NOT IN (
              pl.detailing.crm.invoicing.domain.ExternalInvoiceStatus.PAID,
              pl.detailing.crm.invoicing.domain.ExternalInvoiceStatus.CANCELLED
          )
          AND d.deletedAt IS NULL
        ORDER BY d.issueDate DESC
    """)
    fun findActiveInvoicesForSync(
        studioId: UUID,
        provider: InvoiceProviderType
    ): List<FinancialDocumentEntity>

    /** Finds SYNC_FAILED invoice documents for a specific visit. Used for deduplication on import. */
    @Query("""
        SELECT d FROM FinancialDocumentEntity d
        WHERE d.studioId          = :studioId
          AND d.visitId           = :visitId
          AND d.providerSyncStatus = pl.detailing.crm.invoicing.domain.InvoiceProviderSyncStatus.SYNC_FAILED
          AND d.deletedAt IS NULL
    """)
    fun findSyncFailedByStudioIdAndVisitId(
        studioId: UUID,
        visitId: UUID
    ): List<FinancialDocumentEntity>

    /** All invoices ordered newest-first (for paginated listing). */
    @Query("""
        SELECT d FROM FinancialDocumentEntity d
        WHERE d.studioId = :studioId
          AND d.documentType = pl.detailing.crm.finance.domain.DocumentType.INVOICE
          AND d.deletedAt IS NULL
        ORDER BY d.issueDate DESC, d.createdAt DESC
    """)
    fun findInvoicesByStudioId(studioId: UUID, pageable: Pageable): Page<FinancialDocumentEntity>
}
