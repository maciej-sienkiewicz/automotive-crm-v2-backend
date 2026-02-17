package pl.detailing.crm.customer.documents

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/customers/{customerId}/documents")
class CustomerDocumentController(
    private val customerDocumentService: CustomerDocumentService
) {

    @GetMapping
    fun listDocuments(
        @PathVariable customerId: String
    ): ResponseEntity<List<CustomerDocumentResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val documents = customerDocumentService.listDocuments(
            customerId = UUID.fromString(customerId),
            studioId = principal.studioId.value
        )

        ResponseEntity.ok(documents.map { it.toResponse() })
    }

    /**
     * Initiates a document upload.
     * Returns a presigned S3 URL - frontend should PUT the file directly to that URL.
     */
    @PostMapping
    fun initiateUpload(
        @PathVariable customerId: String,
        @RequestBody request: InitiateDocumentUploadRequest
    ): ResponseEntity<InitiateDocumentUploadResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = customerDocumentService.initiateUpload(
            studioId = principal.studioId.value,
            customerId = UUID.fromString(customerId),
            name = request.name,
            fileName = request.fileName,
            contentType = request.contentType,
            uploadedBy = principal.userId.value,
            uploadedByName = principal.fullName
        )

        ResponseEntity.status(HttpStatus.CREATED).body(
            InitiateDocumentUploadResponse(
                documentId = result.documentId,
                uploadUrl = result.uploadUrl
            )
        )
    }

    @DeleteMapping("/{documentId}")
    fun deleteDocument(
        @PathVariable customerId: String,
        @PathVariable documentId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        customerDocumentService.deleteDocument(
            documentId = UUID.fromString(documentId),
            studioId = principal.studioId.value,
            deletedBy = principal.userId.value,
            deletedByName = principal.fullName
        )

        ResponseEntity.noContent().build()
    }
}

data class InitiateDocumentUploadRequest(
    val name: String,
    val fileName: String,
    val contentType: String
)

data class InitiateDocumentUploadResponse(
    val documentId: String,
    val uploadUrl: String
)

data class CustomerDocumentResponse(
    val id: String,
    val name: String,
    val fileName: String,
    val fileUrl: String,
    val uploadedAt: Instant,
    val uploadedByName: String
)

private fun CustomerDocumentItem.toResponse() = CustomerDocumentResponse(
    id = id,
    name = name,
    fileName = fileName,
    fileUrl = fileUrl,
    uploadedAt = uploadedAt,
    uploadedByName = uploadedByName
)
