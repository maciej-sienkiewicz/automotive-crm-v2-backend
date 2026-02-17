package pl.detailing.crm.customer.documents

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.infrastructure.CustomerDocumentEntity
import pl.detailing.crm.customer.infrastructure.CustomerDocumentRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.visit.infrastructure.DocumentStorageService
import java.time.Instant
import java.util.UUID

@Service
class CustomerDocumentService(
    private val customerDocumentRepository: CustomerDocumentRepository,
    private val documentStorageService: DocumentStorageService
) {
    companion object {
        private val logger = LoggerFactory.getLogger(CustomerDocumentService::class.java)
    }

    @Transactional(readOnly = true)
    fun listDocuments(customerId: UUID, studioId: UUID): List<CustomerDocumentItem> {
        return customerDocumentRepository.findByCustomerIdAndStudioId(customerId, studioId)
            .map { entity ->
                CustomerDocumentItem(
                    id = entity.id.toString(),
                    name = entity.name,
                    fileName = entity.fileName,
                    fileUrl = documentStorageService.generateDownloadUrl(entity.fileId),
                    uploadedAt = entity.uploadedAt,
                    uploadedBy = entity.uploadedBy.toString(),
                    uploadedByName = entity.uploadedByName
                )
            }
    }

    @Transactional
    suspend fun uploadDocument(
        studioId: UUID,
        customerId: UUID,
        name: String,
        fileName: String,
        fileBytes: ByteArray,
        contentType: String,
        uploadedBy: UUID,
        uploadedByName: String
    ): CustomerDocumentItem = withContext(Dispatchers.IO) {
        val extension = when {
            contentType.contains("pdf") -> "pdf"
            contentType.contains("jpeg") || contentType.contains("jpg") -> "jpg"
            contentType.contains("png") -> "png"
            else -> fileName.substringAfterLast('.', "bin")
        }

        val s3Key = "$studioId/customers/$customerId/documents/${Instant.now().toEpochMilli()}_${UUID.randomUUID()}.$extension"

        documentStorageService.uploadDocument(
            s3Key = s3Key,
            fileBytes = fileBytes,
            contentType = contentType,
            metadata = mapOf(
                "studio-id" to studioId.toString(),
                "customer-id" to customerId.toString()
            )
        )

        val entity = CustomerDocumentEntity(
            id = UUID.randomUUID(),
            studioId = studioId,
            customerId = customerId,
            name = name,
            fileName = fileName,
            fileId = s3Key,
            uploadedAt = Instant.now(),
            uploadedBy = uploadedBy,
            uploadedByName = uploadedByName
        )

        val saved = customerDocumentRepository.save(entity)

        logger.info("Uploaded customer document '$name' for customer $customerId")

        CustomerDocumentItem(
            id = saved.id.toString(),
            name = saved.name,
            fileName = saved.fileName,
            fileUrl = documentStorageService.generateDownloadUrl(saved.fileId),
            uploadedAt = saved.uploadedAt,
            uploadedBy = saved.uploadedBy.toString(),
            uploadedByName = saved.uploadedByName
        )
    }

    @Transactional
    suspend fun deleteDocument(documentId: UUID, studioId: UUID): Unit = withContext(Dispatchers.IO) {
        val entity = customerDocumentRepository.findByIdAndStudioId(documentId, studioId)
            ?: throw EntityNotFoundException("Document not found: $documentId")

        customerDocumentRepository.delete(entity)

        try {
            documentStorageService.deleteDocument(entity.fileId)
        } catch (e: Exception) {
            logger.warn("Failed to delete document from S3 (db record removed): ${entity.fileId}", e)
        }

        logger.info("Deleted customer document ${entity.name} (id: $documentId)")
    }
}

data class CustomerDocumentItem(
    val id: String,
    val name: String,
    val fileName: String,
    val fileUrl: String,
    val uploadedAt: Instant,
    val uploadedBy: String,
    val uploadedByName: String
)
