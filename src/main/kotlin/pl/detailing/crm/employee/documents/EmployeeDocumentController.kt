package pl.detailing.crm.employee.documents

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/employees/{employeeId}/documents")
class EmployeeDocumentController(
    private val employeeDocumentService: EmployeeDocumentService
) {

    /**
     * List all documents for a given employee.
     * Returns documents with fresh presigned URLs (15-minute expiry) suitable for inline preview.
     */
    @GetMapping
    fun listDocuments(
        @PathVariable employeeId: String
    ): ResponseEntity<List<EmployeeDocumentResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val documents = employeeDocumentService.listDocuments(
            employeeId = UUID.fromString(employeeId),
            studioId = principal.studioId.value
        )

        ResponseEntity.ok(documents.map { it.toResponse() })
    }

    /**
     * Initiate a document upload.
     * Returns a presigned S3 PUT URL – the client should upload the file directly to that URL.
     */
    @PostMapping
    fun initiateUpload(
        @PathVariable employeeId: String,
        @RequestBody request: InitiateEmployeeDocumentUploadRequest
    ): ResponseEntity<InitiateEmployeeDocumentUploadResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = employeeDocumentService.initiateUpload(
            studioId = principal.studioId.value,
            employeeId = UUID.fromString(employeeId),
            name = request.name,
            fileName = request.fileName,
            contentType = request.contentType,
            uploadedBy = principal.userId.value,
            uploadedByName = principal.fullName
        )

        ResponseEntity.status(HttpStatus.CREATED).body(
            InitiateEmployeeDocumentUploadResponse(
                documentId = result.documentId,
                uploadUrl = result.uploadUrl
            )
        )
    }

    /**
     * Generate a fresh presigned URL for downloading a document (Content-Disposition: attachment).
     * Use this endpoint to trigger a file download in the browser.
     */
    @GetMapping("/{documentId}/download")
    fun getDownloadUrl(
        @PathVariable employeeId: String,
        @PathVariable documentId: String
    ): ResponseEntity<EmployeeDocumentUrlResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val url = employeeDocumentService.getDownloadUrl(
            documentId = UUID.fromString(documentId),
            studioId = principal.studioId.value
        )

        ResponseEntity.ok(EmployeeDocumentUrlResponse(url = url))
    }

    /**
     * Generate a fresh presigned URL for previewing a document inline (e.g. in a PDF viewer).
     * Use this endpoint when you want to display the file inside the browser.
     */
    @GetMapping("/{documentId}/preview")
    fun getPreviewUrl(
        @PathVariable employeeId: String,
        @PathVariable documentId: String
    ): ResponseEntity<EmployeeDocumentUrlResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val url = employeeDocumentService.getPreviewUrl(
            documentId = UUID.fromString(documentId),
            studioId = principal.studioId.value
        )

        ResponseEntity.ok(EmployeeDocumentUrlResponse(url = url))
    }

    /**
     * Delete a document – removes the database record and the corresponding S3 object.
     */
    @DeleteMapping("/{documentId}")
    fun deleteDocument(
        @PathVariable employeeId: String,
        @PathVariable documentId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        employeeDocumentService.deleteDocument(
            documentId = UUID.fromString(documentId),
            studioId = principal.studioId.value,
            deletedBy = principal.userId.value,
            deletedByName = principal.fullName
        )

        ResponseEntity.noContent().build()
    }
}

// ─── Request / Response DTOs ─────────────────────────────────────────────────

data class InitiateEmployeeDocumentUploadRequest(
    val name: String,
    val fileName: String,
    val contentType: String
)

data class InitiateEmployeeDocumentUploadResponse(
    val documentId: String,
    val uploadUrl: String
)

data class EmployeeDocumentUrlResponse(
    val url: String
)

data class EmployeeDocumentResponse(
    val id: String,
    val name: String,
    val fileName: String,
    val fileUrl: String,
    val uploadedAt: Instant,
    val uploadedByName: String
)

private fun EmployeeDocumentItem.toResponse() = EmployeeDocumentResponse(
    id = id,
    name = name,
    fileName = fileName,
    fileUrl = fileUrl,
    uploadedAt = uploadedAt,
    uploadedByName = uploadedByName
)
