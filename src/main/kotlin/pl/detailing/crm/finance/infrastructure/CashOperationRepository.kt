package pl.detailing.crm.finance.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CashOperationRepository : JpaRepository<CashOperationEntity, UUID> {

    /**
     * Returns the full, chronologically-ordered operation history for a studio.
     * Sorted by [CashOperationEntity.createdAt] descending (newest first).
     */
    @Query("""
        SELECT op FROM CashOperationEntity op
        WHERE op.studioId = :studioId
        ORDER BY op.createdAt DESC
    """)
    fun findByStudioId(studioId: UUID, pageable: Pageable): Page<CashOperationEntity>

    /**
     * Finds all operations linked to a specific financial document.
     * Typically zero or one result (PAYMENT_IN / PAYMENT_OUT).
     */
    @Query("""
        SELECT op FROM CashOperationEntity op
        WHERE op.studioId           = :studioId
          AND op.financialDocumentId = :documentId
        ORDER BY op.createdAt ASC
    """)
    fun findByDocumentId(studioId: UUID, documentId: UUID): List<CashOperationEntity>
}
