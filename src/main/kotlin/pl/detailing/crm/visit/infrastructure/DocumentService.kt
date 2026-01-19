package pl.detailing.crm.visit.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.domain.VisitDocument
import java.time.Instant
import java.util.UUID

/**
 * Service for managing visit documents
 *
 * Handles:
 * - Document registration (auto-registration from PDF generators)
 * - Document retrieval (by visit, by customer)
 * - Document deletion (database + S3)
 * - External document uploads
 * - Presigned URL generation for secure access
 */
@Service
class DocumentService(
    private val visitDocumentRepository: VisitDocumentRepository,
    private val visitRepository: VisitRepository,
    private val documentStorageService: DocumentStorageService
) {

    companion object {
        private val logger = LoggerFactory.getLogger(DocumentService::class.java)
    }

    /**
     * Register a new document (auto-registration from generators like damage map service)
     *
     * This method is called when a document (like a PDF protocol or damage map) is generated
     * and needs to be registered in the database.
     *
     * @param visitId The visit ID
     * @param customerId The customer ID
     * @param documentType The type of document
     * @param name Human-readable title (e.g., "Intake Protocol - PO12345")
     * @param s3Key The S3 key where the document is stored
     * @param fileName The original file name
     * @param createdBy The user who created/generated the document
     * @param createdByName The name of the user who created the document
     * @param category Optional category for organization
     * @return The created VisitDocument domain model
     */
    @Transactional
    suspend fun registerDocument(
        visitId: UUID,
        customerId: UUID,
        documentType: DocumentType,
        name: String,
        s3Key: String,
        fileName: String,
        createdBy: UUID,
        createdByName: String,
        category: String? = null
    ): VisitDocument = withContext(Dispatchers.IO) {
        try {
            // Verify visit exists
            val visit = visitRepository.findById(visitId).orElseThrow {
                IllegalArgumentException("Visit not found: $visitId")
            }

            // Generate presigned URL (will be regenerated on access, but store initial one)
            val fileUrl = documentStorageService.generateDownloadUrl(s3Key)

            // Create entity
            val documentEntity = VisitDocumentEntity(
                id = UUID.randomUUID(),
                visit = visit,
                customerId = customerId,
                type = documentType,
                name = name,
                fileName = fileName,
                fileId = s3Key, // Using S3 key as file ID
                fileUrl = fileUrl,
                uploadedAt = Instant.now(),
                uploadedBy = createdBy,
                uploadedByName = createdByName,
                category = category
            )

            val savedEntity = visitDocumentRepository.save(documentEntity)

            logger.info("Registered document: $name (type: $documentType) for visit $visitId")

            return@withContext savedEntity.toDomain()

        } catch (e: Exception) {
            logger.error("Failed to register document for visit $visitId", e)
            throw IllegalStateException("Failed to register document: ${e.message}", e)
        }
    }

    /**
     * Get all documents for a specific visit
     * Returns documents with fresh presigned URLs (15-minute expiry)
     *
     * @param visitId The visit ID
     * @param studioId The studio ID (for authorization)
     * @return List of documents with fresh download URLs
     */
    @Transactional(readOnly = true)
    fun getDocumentsByVisit(visitId: UUID, studioId: UUID): List<VisitDocument> {
        // Verify visit exists and belongs to studio
        val visit = visitRepository.findByIdAndStudioId(visitId, studioId)
            ?: throw IllegalArgumentException("Visit not found or access denied: $visitId")

        val documents = visitDocumentRepository.findByVisit_IdOrderByUploadedAtDesc(visitId)

        // Return documents with fresh presigned URLs
        return documents.map { entity ->
            entity.toDomain().copy(
                fileUrl = documentStorageService.generateDownloadUrl(entity.fileId)
            )
        }
    }

    /**
     * Get all documents for a customer across all visits
     * Returns aggregated customer history with fresh presigned URLs
     *
     * @param customerId The customer ID
     * @param studioId The studio ID (for authorization)
     * @return List of all customer documents sorted by date (newest first)
     */
    @Transactional(readOnly = true)
    fun getDocumentsByCustomer(customerId: UUID, studioId: UUID): List<VisitDocument> {
        // Note: We should add a customer ownership check here
        // For now, we'll rely on the fact that documents are linked to visits which have studioId

        val documents = visitDocumentRepository.findByCustomerIdOrderByUploadedAtDesc(customerId)

        // Filter by studio (documents from visits belonging to this studio)
        val filteredDocuments = documents.filter { entity ->
            entity.visit.studioId == studioId
        }

        // Return documents with fresh presigned URLs
        return filteredDocuments.map { entity ->
            entity.toDomain().copy(
                fileUrl = documentStorageService.generateDownloadUrl(entity.fileId)
            )
        }
    }

    /**
     * Upload an external document (e.g., scanned registration certificate)
     *
     * This is used when users manually upload documents that aren't auto-generated
     *
     * @param visitId The visit ID
     * @param studioId The studio ID
     * @param customerId The customer ID
     * @param documentType The type of document (usually OTHER)
     * @param name Human-readable title
     * @param fileName Original file name
     * @param fileBytes The file bytes
     * @param contentType MIME content type
     * @param uploadedBy The user uploading the document
     * @param uploadedByName The name of the user
     * @param category Optional category
     * @return The created document
     */
    @Transactional
    suspend fun uploadExternalDocument(
        visitId: UUID,
        studioId: UUID,
        customerId: UUID,
        documentType: DocumentType,
        name: String,
        fileName: String,
        fileBytes: ByteArray,
        contentType: String,
        uploadedBy: UUID,
        uploadedByName: String,
        category: String? = null
    ): VisitDocument = withContext(Dispatchers.IO) {
        try {
            // Verify visit exists and belongs to studio
            val visit = visitRepository.findByIdAndStudioId(visitId, studioId)
                ?: throw IllegalArgumentException("Visit not found or access denied: $visitId")

            // Determine file extension from content type
            val extension = when (contentType) {
                "application/pdf" -> "pdf"
                "image/jpeg", "image/jpg" -> "jpg"
                "image/png" -> "png"
                else -> "pdf"
            }

            // Build S3 key
            val s3Key = documentStorageService.buildDocumentS3Key(
                studioId = studioId,
                customerId = customerId,
                visitId = visitId,
                documentType = documentType,
                extension = extension
            )

            // Upload to S3
            val uploadedKey = documentStorageService.uploadDocument(
                s3Key = s3Key,
                fileBytes = fileBytes,
                contentType = contentType,
                metadata = mapOf(
                    "studio-id" to studioId.toString(),
                    "customer-id" to customerId.toString(),
                    "visit-id" to visitId.toString(),
                    "document-type" to documentType.name
                )
            )

            // Register in database
            return@withContext registerDocument(
                visitId = visitId,
                customerId = customerId,
                documentType = documentType,
                name = name,
                s3Key = uploadedKey,
                fileName = fileName,
                createdBy = uploadedBy,
                createdByName = uploadedByName,
                category = category
            )

        } catch (e: Exception) {
            logger.error("Failed to upload external document for visit $visitId", e)
            throw IllegalStateException("Failed to upload document: ${e.message}", e)
        }
    }

    /**
     * Delete a document (removes from database and S3)
     *
     * @param documentId The document ID
     * @param studioId The studio ID (for authorization)
     */
    @Transactional
    suspend fun deleteDocument(documentId: UUID, studioId: UUID): Unit = withContext(Dispatchers.IO) {
        try {
            // Find document
            val documentEntity = visitDocumentRepository.findById(documentId).orElseThrow {
                IllegalArgumentException("Document not found: $documentId")
            }

            // Verify studio ownership
            if (documentEntity.visit.studioId != studioId) {
                throw IllegalArgumentException("Access denied: Document does not belong to this studio")
            }

            val s3Key = documentEntity.fileId

            // Delete from database first
            visitDocumentRepository.delete(documentEntity)

            // Then delete from S3
            try {
                documentStorageService.deleteDocument(s3Key)
            } catch (e: Exception) {
                // Log but don't fail - database record is already deleted
                logger.warn("Failed to delete document from S3 (database record already deleted): $s3Key", e)
            }

            logger.info("Deleted document: ${documentEntity.name} (ID: $documentId)")

        } catch (e: Exception) {
            logger.error("Failed to delete document $documentId", e)
            throw IllegalStateException("Failed to delete document: ${e.message}", e)
        }
    }

    /**
     * Generate a fresh download URL for a document
     * Useful when the frontend needs to refresh an expired URL
     *
     * @param documentId The document ID
     * @param studioId The studio ID (for authorization)
     * @return Fresh presigned download URL (15-minute expiry)
     */
    @Transactional(readOnly = true)
    fun generateDocumentDownloadUrl(documentId: UUID, studioId: UUID): String {
        val documentEntity = visitDocumentRepository.findById(documentId).orElseThrow {
            IllegalArgumentException("Document not found: $documentId")
        }

        // Verify studio ownership
        if (documentEntity.visit.studioId != studioId) {
            throw IllegalArgumentException("Access denied: Document does not belong to this studio")
        }

        return documentStorageService.generateDownloadUrl(documentEntity.fileId)
    }
}
