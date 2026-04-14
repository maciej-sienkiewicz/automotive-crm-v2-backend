package pl.detailing.crm.employee.documents

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.infrastructure.EmployeeDocumentEntity
import pl.detailing.crm.employee.infrastructure.EmployeeDocumentRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.visit.infrastructure.DocumentStorageService
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class EmployeeDocumentService(
    private val employeeDocumentRepository: EmployeeDocumentRepository,
    private val documentStorageService: DocumentStorageService,
    private val s3Presigner: S3Presigner,
    private val auditService: AuditService,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String
) {
    companion object {
        private val logger = LoggerFactory.getLogger(EmployeeDocumentService::class.java)
        private val URL_DURATION = Duration.ofMinutes(15)
    }

    @Transactional(readOnly = true)
    suspend fun listDocuments(employeeId: UUID, studioId: UUID): List<EmployeeDocumentItem> =
        withContext(Dispatchers.IO) {
            employeeDocumentRepository.findByEmployeeIdAndStudioId(employeeId, studioId)
                .map { entity ->
                    EmployeeDocumentItem(
                        id = entity.id.toString(),
                        name = entity.name,
                        fileName = entity.fileName,
                        fileUrl = documentStorageService.generateDownloadUrl(entity.fileId),
                        uploadedAt = entity.uploadedAt,
                        uploadedByName = entity.uploadedByName
                    )
                }
        }

    @Transactional(readOnly = true)
    suspend fun getDownloadUrl(documentId: UUID, studioId: UUID): String =
        withContext(Dispatchers.IO) {
            val entity = employeeDocumentRepository.findByIdAndStudioId(documentId, studioId)
                ?: throw EntityNotFoundException("Document not found: $documentId")
            generateAttachmentUrl(entity.fileId, entity.fileName)
        }

    @Transactional(readOnly = true)
    suspend fun getPreviewUrl(documentId: UUID, studioId: UUID): String =
        withContext(Dispatchers.IO) {
            val entity = employeeDocumentRepository.findByIdAndStudioId(documentId, studioId)
                ?: throw EntityNotFoundException("Document not found: $documentId")
            documentStorageService.generateDownloadUrl(entity.fileId)
        }

    @Transactional
    suspend fun initiateUpload(
        studioId: UUID,
        employeeId: UUID,
        name: String,
        fileName: String,
        contentType: String,
        uploadedBy: UUID,
        uploadedByName: String
    ): EmployeeDocumentUploadResult = withContext(Dispatchers.IO) {
        val extension = fileName.substringAfterLast('.', "bin")
        val s3Key = "$studioId/employees/$employeeId/documents/${Instant.now().toEpochMilli()}_${UUID.randomUUID()}.$extension"

        val entity = EmployeeDocumentEntity(
            id = UUID.randomUUID(),
            studioId = studioId,
            employeeId = employeeId,
            name = name,
            fileName = fileName,
            fileId = s3Key,
            uploadedAt = Instant.now(),
            uploadedBy = uploadedBy,
            uploadedByName = uploadedByName
        )

        val saved = employeeDocumentRepository.save(entity)
        val uploadUrl = documentStorageService.generateUploadUrl(s3Key, contentType)

        logger.info("Initiated employee document upload '$name' for employee $employeeId")

        auditService.log(LogAuditCommand(
            studioId = StudioId(studioId),
            userId = UserId(uploadedBy),
            userDisplayName = uploadedByName,
            module = AuditModule.EMPLOYEE,
            entityId = employeeId.toString(),
            action = AuditAction.DOCUMENT_ADDED,
            changes = listOf(
                FieldChange("documentName", null, name),
                FieldChange("fileName", null, fileName)
            ),
            metadata = mapOf("documentId" to saved.id.toString())
        ))

        EmployeeDocumentUploadResult(
            documentId = saved.id.toString(),
            uploadUrl = uploadUrl
        )
    }

    @Transactional
    suspend fun deleteDocument(
        documentId: UUID,
        studioId: UUID,
        deletedBy: UUID,
        deletedByName: String
    ): Unit = withContext(Dispatchers.IO) {
        val entity = employeeDocumentRepository.findByIdAndStudioId(documentId, studioId)
            ?: throw EntityNotFoundException("Document not found: $documentId")

        val docName = entity.name
        val employeeId = entity.employeeId

        employeeDocumentRepository.delete(entity)

        try {
            documentStorageService.deleteDocument(entity.fileId)
        } catch (e: Exception) {
            logger.warn("Failed to delete document from S3 (db record removed): ${entity.fileId}", e)
        }

        logger.info("Deleted employee document ${entity.name} (id: $documentId)")

        auditService.log(LogAuditCommand(
            studioId = StudioId(studioId),
            userId = UserId(deletedBy),
            userDisplayName = deletedByName,
            module = AuditModule.EMPLOYEE,
            entityId = employeeId.toString(),
            action = AuditAction.DOCUMENT_DELETED,
            changes = listOf(FieldChange("documentName", docName, null)),
            metadata = mapOf("documentId" to documentId.toString())
        ))
    }

    private fun generateAttachmentUrl(s3Key: String, fileName: String): String {
        val getObjectRequest = GetObjectRequest.builder()
            .bucket(bucketName)
            .key(s3Key)
            .responseContentDisposition("attachment; filename=\"$fileName\"")
            .build()

        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(URL_DURATION)
            .getObjectRequest(getObjectRequest)
            .build()

        return s3Presigner.presignGetObject(presignRequest).url().toString()
    }
}

data class EmployeeDocumentItem(
    val id: String,
    val name: String,
    val fileName: String,
    val fileUrl: String,
    val uploadedAt: Instant,
    val uploadedByName: String
)

data class EmployeeDocumentUploadResult(
    val documentId: String,
    val uploadUrl: String
)
