package pl.detailing.crm.visit.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Repository for VisitDocument entities
 *
 * Provides database operations for document management including:
 * - Visit-specific document queries
 * - Customer-wide document history queries
 * - Document deletion
 */
@Repository
interface VisitDocumentRepository : JpaRepository<VisitDocumentEntity, UUID> {

    /**
     * Find all documents for a specific visit
     * Ordered by upload date descending (newest first)
     */
    fun findByVisit_IdOrderByUploadedAtDesc(visitId: UUID): List<VisitDocumentEntity>

    /**
     * Find all documents for a specific customer across all visits
     * Ordered by upload date descending (newest first)
     * This uses the denormalized customer_id for fast queries
     */
    fun findByCustomerIdOrderByUploadedAtDesc(customerId: UUID): List<VisitDocumentEntity>

    /**
     * Find all documents for a specific visit and customer
     */
    fun findByVisit_IdAndCustomerId(visitId: UUID, customerId: UUID): List<VisitDocumentEntity>

    /**
     * Check if a document exists for a visit
     */
    fun existsByVisit_Id(visitId: UUID): Boolean

    /**
     * Delete all documents for a specific visit
     * Note: This should be called before visit deletion
     */
    fun deleteByVisit_Id(visitId: UUID)
}
