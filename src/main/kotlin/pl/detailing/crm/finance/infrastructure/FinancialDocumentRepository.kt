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
import java.time.LocalDate
import java.util.UUID

@Repository
interface FinancialDocumentRepository : JpaRepository<FinancialDocumentEntity, UUID> {

    @Query("SELECT d FROM FinancialDocumentEntity d WHERE d.id = :id AND d.studioId = :studioId AND d.deletedAt IS NULL")
    fun findByIdAndStudioId(id: UUID, studioId: UUID): FinancialDocumentEntity?

    @Query("SELECT d FROM FinancialDocumentEntity d WHERE d.id = :id AND d.studioId = :studioId")
    fun findByIdAndStudioIdIncludingDeleted(id: UUID, studioId: UUID): FinancialDocumentEntity?

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

    @Query("""
        SELECT d FROM FinancialDocumentEntity d
        WHERE d.studioId = :studioId
          AND (:includeDeleted = true OR d.deletedAt IS NULL)
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
        includeDeleted: Boolean,
        pageable: Pageable
    ): Page<FinancialDocumentEntity>

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

    @Query("""
        SELECT COUNT(d) FROM FinancialDocumentEntity d
        WHERE d.studioId  = :studioId
          AND d.status    = 'OVERDUE'
          AND d.deletedAt IS NULL
          AND (:direction IS NULL OR d.direction = :direction)
    """)
    fun countOverdue(studioId: UUID, direction: DocumentDirection?): Long

    @Query("""
        SELECT d FROM FinancialDocumentEntity d
        WHERE d.studioId  = :studioId
          AND d.status    = 'PENDING'
          AND d.dueDate   < :today
          AND d.deletedAt IS NULL
    """)
    fun findPendingOverdue(studioId: UUID, today: LocalDate): List<FinancialDocumentEntity>

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

    @Query(value = """
        SELECT * FROM financial_documents d
        WHERE d.studio_id  = CAST(:studioId AS uuid)
          AND d.direction  = 'INCOME'
          AND d.status     = 'PAID'
          AND d.deleted_at IS NULL
          AND (CAST(:documentType AS text) IS NULL OR d.document_type = CAST(:documentType AS text))
          AND (CAST(:dateFrom AS text) IS NULL OR d.issue_date >= CAST(:dateFrom AS date))
          AND (CAST(:dateTo   AS text) IS NULL OR d.issue_date <= CAST(:dateTo   AS date))
        ORDER BY d.issue_date ASC
    """, nativeQuery = true)
    fun findPaidIncomeForReport(
        studioId: UUID,
        documentType: String?,
        dateFrom: LocalDate?,
        dateTo: LocalDate?
    ): List<FinancialDocumentEntity>
}
