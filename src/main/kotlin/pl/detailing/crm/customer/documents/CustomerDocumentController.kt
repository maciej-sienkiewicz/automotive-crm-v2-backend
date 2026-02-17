package pl.detailing.crm.customer.documents

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.CustomerId
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
    ): ResponseEntity<List<CustomerDocumentResponse>> {
        val principal = SecurityContextHelper.getCurrentUser()

        val documents = customerDocumentService.listDocuments(
            customerId = UUID.fromString(customerId),
            studioId = principal.studioId.value
        )

        return ResponseEntity.ok(documents.map { it.toResponse() })
    }

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadDocument(
        @PathVariable customerId: String,
        @RequestParam name: String,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<CustomerDocumentResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (file.isEmpty) {
            return@runBlocking ResponseEntity.badRequest().build()
        }

        val document = customerDocumentService.uploadDocument(
            studioId = principal.studioId.value,
            customerId = UUID.fromString(customerId),
            name = name,
            fileName = file.originalFilename ?: file.name,
            fileBytes = file.bytes,
            contentType = file.contentType ?: "application/octet-stream",
            uploadedBy = principal.userId.value,
            uploadedByName = principal.fullName
        )

        ResponseEntity.status(HttpStatus.CREATED).body(document.toResponse())
    }

    @DeleteMapping("/{documentId}")
    fun deleteDocument(
        @PathVariable customerId: String,
        @PathVariable documentId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        customerDocumentService.deleteDocument(
            documentId = UUID.fromString(documentId),
            studioId = principal.studioId.value
        )

        ResponseEntity.noContent().build()
    }
}

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
