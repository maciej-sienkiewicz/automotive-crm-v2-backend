package pl.detailing.crm.visit.document

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.DocumentService
import java.time.Instant
import java.util.UUID

/**
 * REST controller for document management
 *
 * Endpoints:
 * - GET /api/visits/{visitId}/documents - Get all documents for a visit
 * - GET /api/customers/{customerId}/documents - Get all documents for a customer
 * - POST /api/documents/external - Upload an external document
 * - DELETE /api/documents/{id} - Delete a document
 */
@RestController
@RequestMapping("/api")
class DocumentController(
    private val documentService: DocumentService
) {

    /**
     * Get all documents for a specific visit
     * Returns documents with fresh presigned URLs (15-minute expiry)
     *
     * GET /api/visits/{visitId}/documents
     */
    @GetMapping("/visits/{visitId}/documents")
    fun getVisitDocuments(
        @PathVariable visitId: String
    ): ResponseEntity<List<DocumentResponse>> {
        val principal = SecurityContextHelper.getCurrentUser()

        val documents = documentService.getDocumentsByVisit(
            visitId = UUID.fromString(visitId),
            studioId = principal.studioId.value
        )

        val response = documents.map { doc ->
            DocumentResponse(
                id = doc.id.value.toString(),
                visitId = visitId,
                customerId = doc.customerId.value.toString(),
                type = doc.type,
                name = doc.name,
                fileName = doc.fileName,
                fileUrl = doc.fileUrl,
                uploadedAt = doc.uploadedAt,
                uploadedBy = doc.uploadedBy.value.toString(),
                uploadedByName = doc.uploadedByName,
                category = doc.category
            )
        }

        return ResponseEntity.ok(response)
    }

    /**
     * Get all documents for a customer across all visits
     * Returns aggregated customer history with fresh presigned URLs
     *
     * GET /api/customers/{customerId}/documents
     */
    @GetMapping("/customers/{customerId}/documents")
    fun getCustomerDocuments(
        @PathVariable customerId: String
    ): ResponseEntity<List<DocumentResponse>> {
        val principal = SecurityContextHelper.getCurrentUser()

        val documents = documentService.getDocumentsByCustomer(
            customerId = UUID.fromString(customerId),
            studioId = principal.studioId.value
        )

        val response = documents.map { doc ->
            // Note: visitId is stored in the document entity but not in domain model
            // We'll need to fetch it or add it to the domain model
            DocumentResponse(
                id = doc.id.value.toString(),
                visitId = "", // Will be populated from entity if needed
                customerId = doc.customerId.value.toString(),
                type = doc.type,
                name = doc.name,
                fileName = doc.fileName,
                fileUrl = doc.fileUrl,
                uploadedAt = doc.uploadedAt,
                uploadedBy = doc.uploadedBy.value.toString(),
                uploadedByName = doc.uploadedByName,
                category = doc.category
            )
        }

        return ResponseEntity.ok(response)
    }

    /**
     * Upload an external document (e.g., scanned registration certificate)
     *
     * POST /api/documents/external
     * Content-Type: multipart/form-data
     */
    @PostMapping("/documents/external", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadExternalDocument(
        @RequestParam visitId: String,
        @RequestParam customerId: String,
        @RequestParam type: DocumentType,
        @RequestParam name: String,
        @RequestParam(required = false) category: String?,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<DocumentResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        // Validate file
        if (file.isEmpty) {
            return@runBlocking ResponseEntity.badRequest().build()
        }

        // Upload document
        val document = documentService.uploadExternalDocument(
            visitId = UUID.fromString(visitId),
            studioId = principal.studioId.value,
            customerId = UUID.fromString(customerId),
            documentType = type,
            name = name,
            fileName = file.originalFilename ?: "unknown",
            fileBytes = file.bytes,
            contentType = file.contentType ?: "application/pdf",
            uploadedBy = principal.userId.value,
            uploadedByName = principal.fullName,
            category = category
        )

        val response = DocumentResponse(
            id = document.id.value.toString(),
            visitId = visitId,
            customerId = document.customerId.value.toString(),
            type = document.type,
            name = document.name,
            fileName = document.fileName,
            fileUrl = document.fileUrl,
            uploadedAt = document.uploadedAt,
            uploadedBy = document.uploadedBy.value.toString(),
            uploadedByName = document.uploadedByName,
            category = document.category
        )

        return@runBlocking ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    /**
     * Delete a document (removes from database and S3)
     *
     * DELETE /api/documents/{id}
     */
    @DeleteMapping("/documents/{id}")
    fun deleteDocument(
        @PathVariable id: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        documentService.deleteDocument(
            documentId = UUID.fromString(id),
            studioId = principal.studioId.value
        )

        return@runBlocking ResponseEntity.noContent().build()
    }

    /**
     * Generate a fresh download URL for a document
     * Useful when the frontend needs to refresh an expired URL
     *
     * GET /api/documents/{id}/download-url
     */
    @GetMapping("/documents/{id}/download-url")
    fun getDocumentDownloadUrl(
        @PathVariable id: String
    ): ResponseEntity<DownloadUrlResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val downloadUrl = documentService.generateDocumentDownloadUrl(
            documentId = UUID.fromString(id),
            studioId = principal.studioId.value
        )

        return ResponseEntity.ok(DownloadUrlResponse(url = downloadUrl))
    }
}

// DTOs

data class DocumentResponse(
    val id: String,
    val visitId: String,
    val customerId: String,
    val type: DocumentType,
    val name: String,
    val fileName: String,
    val fileUrl: String,
    val uploadedAt: Instant,
    val uploadedBy: String,
    val uploadedByName: String,
    val category: String?
)

data class DownloadUrlResponse(
    val url: String
)
