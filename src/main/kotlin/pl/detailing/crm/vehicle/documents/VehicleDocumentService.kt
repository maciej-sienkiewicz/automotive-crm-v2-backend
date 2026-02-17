package pl.detailing.crm.vehicle.documents

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.vehicle.infrastructure.VehicleDocumentEntity
import pl.detailing.crm.vehicle.infrastructure.VehicleDocumentRepository
import pl.detailing.crm.visit.infrastructure.DocumentStorageService
import java.time.Instant
import java.util.UUID

@Service
class VehicleDocumentService(
    private val vehicleDocumentRepository: VehicleDocumentRepository,
    private val documentStorageService: DocumentStorageService,
    private val auditService: AuditService
) {
    companion object {
        private val logger = LoggerFactory.getLogger(VehicleDocumentService::class.java)
    }

    /**
     * Creates the document record in DB and returns a presigned S3 upload URL.
     * The frontend uploads the file directly to S3 using the returned URL.
     */
    @Transactional
    suspend fun initiateUpload(
        studioId: UUID,
        vehicleId: UUID,
        name: String,
        fileName: String,
        contentType: String,
        uploadedBy: UUID,
        uploadedByName: String
    ): VehicleDocumentUploadResult = withContext(Dispatchers.IO) {
        val extension = fileName.substringAfterLast('.', "bin")
        val s3Key = "$studioId/vehicles/$vehicleId/documents/${Instant.now().toEpochMilli()}_${UUID.randomUUID()}.$extension"

        val entity = VehicleDocumentEntity(
            id = UUID.randomUUID(),
            studioId = studioId,
            vehicleId = vehicleId,
            name = name,
            fileName = fileName,
            fileId = s3Key,
            uploadedAt = Instant.now(),
            uploadedBy = uploadedBy,
            uploadedByName = uploadedByName
        )

        val saved = vehicleDocumentRepository.save(entity)
        val uploadUrl = documentStorageService.generateUploadUrl(s3Key, contentType)

        logger.info("Initiated vehicle document upload '$name' for vehicle $vehicleId")

        auditService.log(LogAuditCommand(
            studioId = StudioId(studioId),
            userId = UserId(uploadedBy),
            userDisplayName = uploadedByName,
            module = AuditModule.VEHICLE,
            entityId = vehicleId.toString(),
            action = AuditAction.DOCUMENT_ADDED,
            changes = listOf(
                FieldChange("documentName", null, name),
                FieldChange("fileName", null, fileName)
            ),
            metadata = mapOf("documentId" to saved.id.toString())
        ))

        VehicleDocumentUploadResult(
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
        val entity = vehicleDocumentRepository.findByIdAndStudioId(documentId, studioId)
            ?: throw EntityNotFoundException("Document not found: $documentId")

        val docName = entity.name
        val vehicleId = entity.vehicleId

        vehicleDocumentRepository.delete(entity)

        try {
            documentStorageService.deleteDocument(entity.fileId)
        } catch (e: Exception) {
            logger.warn("Failed to delete document from S3 (db record removed): ${entity.fileId}", e)
        }

        logger.info("Deleted vehicle document ${entity.name} (id: $documentId)")

        if (deletedBy != null) {
            auditService.log(LogAuditCommand(
                studioId = StudioId(studioId),
                userId = UserId(deletedBy),
                userDisplayName = deletedByName ?: "",
                module = AuditModule.VEHICLE,
                entityId = vehicleId.toString(),
                action = AuditAction.DOCUMENT_DELETED,
                changes = listOf(FieldChange("documentName", docName, null)),
                metadata = mapOf("documentId" to documentId.toString())
            ))
        }
    }
}

data class VehicleDocumentUploadResult(
    val documentId: String,
    val uploadUrl: String
)
