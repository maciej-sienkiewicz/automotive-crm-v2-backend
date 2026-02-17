package pl.detailing.crm.customer.documents

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.customer.infrastructure.CustomerDocumentEntity
import pl.detailing.crm.customer.infrastructure.CustomerDocumentRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.visit.infrastructure.DocumentStorageService
import java.time.Instant
import java.util.UUID

@Service
class CustomerDocumentService(
    private val customerDocumentRepository: CustomerDocumentRepository,
    private val documentStorageService: DocumentStorageService,
    private val auditService: AuditService
) {
    companion object {
        private val logger = LoggerFactory.getLogger(CustomerDocumentService::class.java)
    }

    @Transactional(readOnly = true)
    suspend fun listDocuments(customerId: UUID, studioId: UUID): List<CustomerDocumentItem> =
        withContext(Dispatchers.IO) {
            customerDocumentRepository.findByCustomerIdAndStudioId(customerId, studioId)
                .map { entity ->
                    CustomerDocumentItem(
                        id = entity.id.toString(),
                        name = entity.name,
                        fileName = entity.fileName,
                        fileUrl = documentStorageService.generateDownloadUrl(entity.fileId),
                        uploadedAt = entity.uploadedAt,
                        uploadedByName = entity.uploadedByName
                    )
                }
        }

    @Transactional
    suspend fun initiateUpload(
        studioId: UUID,
        customerId: UUID,
        name: String,
        fileName: String,
        contentType: String,
        uploadedBy: UUID,
        uploadedByName: String
    ): CustomerDocumentUploadResult = withContext(Dispatchers.IO) {
        val extension = fileName.substringAfterLast('.', "bin")
        val s3Key = "$studioId/customers/$customerId/documents/${Instant.now().toEpochMilli()}_${UUID.randomUUID()}.$extension"

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
        val uploadUrl = documentStorageService.generateUploadUrl(s3Key, contentType)

        logger.info("Initiated customer document upload '$name' for customer $customerId")

        auditService.log(LogAuditCommand(
            studioId = StudioId(studioId),
            userId = UserId(uploadedBy),
            userDisplayName = uploadedByName,
            module = AuditModule.CUSTOMER,
            entityId = customerId.toString(),
            action = AuditAction.DOCUMENT_ADDED,
            changes = listOf(
                FieldChange("documentName", null, name),
                FieldChange("fileName", null, fileName)
            ),
            metadata = mapOf("documentId" to saved.id.toString())
        ))

        CustomerDocumentUploadResult(
            documentId = saved.id.toString(),
            uploadUrl = uploadUrl
        )
    }

    @Transactional
    suspend fun deleteDocument(
        documentId: UUID,
        studioId: UUID,
        deletedBy: UUID? = null,
        deletedByName: String? = null
    ): Unit = withContext(Dispatchers.IO) {
        val entity = customerDocumentRepository.findByIdAndStudioId(documentId, studioId)
            ?: throw EntityNotFoundException("Document not found: $documentId")

        val docName = entity.name
        val customerId = entity.customerId

        customerDocumentRepository.delete(entity)

        try {
            documentStorageService.deleteDocument(entity.fileId)
        } catch (e: Exception) {
            logger.warn("Failed to delete document from S3 (db record removed): ${entity.fileId}", e)
        }

        logger.info("Deleted customer document ${entity.name} (id: $documentId)")

        if (deletedBy != null) {
            auditService.log(LogAuditCommand(
                studioId = StudioId(studioId),
                userId = UserId(deletedBy),
                userDisplayName = deletedByName ?: "",
                module = AuditModule.CUSTOMER,
                entityId = customerId.toString(),
                action = AuditAction.DOCUMENT_DELETED,
                changes = listOf(FieldChange("documentName", docName, null)),
                metadata = mapOf("documentId" to documentId.toString())
            ))
        }
    }
}

data class CustomerDocumentItem(
    val id: String,
    val name: String,
    val fileName: String,
    val fileUrl: String,
    val uploadedAt: Instant,
    val uploadedByName: String
)

data class CustomerDocumentUploadResult(
    val documentId: String,
    val uploadUrl: String
)
