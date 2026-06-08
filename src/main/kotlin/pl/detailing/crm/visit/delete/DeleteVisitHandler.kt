package pl.detailing.crm.visit.delete

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.protocol.infrastructure.VisitProtocolRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.DocumentService
import pl.detailing.crm.visit.infrastructure.S3DamageMapStorageService
import pl.detailing.crm.visit.infrastructure.VisitCommentRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest

/**
 * Trwale usuwa wizytę niezależnie od jej statusu.
 *
 * Usuwa w kolejności:
 * 1. Pliki zdjęć z S3 (rekordy DB usuwane kaskadowo przez JPA)
 * 2. Pliki protokołów z S3 oraz rekordy protokołów z DB
 * 3. Mapę uszkodzeń z S3
 * 4. Dokumenty z DB i S3 (przez DocumentService)
 * 5. Komentarze i notatki (twarde usunięcie z DB)
 * 6. Wizytę z DB (kaskadowo usuwa: service items, zdjęcia, wpisy dziennika)
 *
 * Rezerwacja (appointment) pozostaje niezmieniona.
 */
@Service
class DeleteVisitHandler(
    private val visitRepository: VisitRepository,
    private val visitProtocolRepository: VisitProtocolRepository,
    private val visitCommentRepository: VisitCommentRepository,
    private val s3ProtocolStorageService: S3ProtocolStorageService,
    private val s3DamageMapStorageService: S3DamageMapStorageService,
    private val documentService: DocumentService,
    private val s3Client: S3Client,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String,
    private val auditService: AuditService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(command: DeleteVisitCommand): Unit = withContext(Dispatchers.IO) {
        val visitEntity = visitRepository.findByIdAndStudioIdWithPhotos(
            command.visitId.value,
            command.studioId.value
        ) ?: throw EntityNotFoundException("Wizyta nie została znaleziona")

        val visitNumber = visitEntity.visitNumber

        // 1. Delete photos from S3 (DB records will be cascade-deleted with the visit entity)
        visitEntity.photos.forEach { photo ->
            try {
                s3Client.deleteObject(
                    DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(photo.fileId)
                        .build()
                )
            } catch (e: Exception) {
                logger.error("Failed to delete photo from S3 [fileId=${photo.fileId}]: ${e.message}", e)
            }
        }

        // 2. Delete protocol files from S3, then protocol records from DB
        val protocols = visitProtocolRepository.findAllByVisitIdAndStudioId(
            command.visitId.value,
            command.studioId.value
        )
        protocols.forEach { protocol ->
            try {
                protocol.filledPdfS3Key?.let { s3ProtocolStorageService.deleteFile(it) }
                protocol.signedPdfS3Key?.let { s3ProtocolStorageService.deleteFile(it) }
                protocol.signatureImageS3Key?.let { s3ProtocolStorageService.deleteFile(it) }
            } catch (e: Exception) {
                logger.error("Failed to delete protocol S3 files [protocolId=${protocol.id}]: ${e.message}", e)
            }
            visitProtocolRepository.delete(protocol)
        }

        // 3. Delete damage map from S3
        visitEntity.damageMapFileId?.let { s3Key ->
            try {
                s3DamageMapStorageService.deleteFile(s3Key)
            } catch (e: Exception) {
                logger.error("Failed to delete damage map from S3 [key=$s3Key]: ${e.message}", e)
            }
        }

        // 4. Delete all documents (DB + S3)
        try {
            documentService.deleteAllDocumentsForVisit(command.visitId.value)
        } catch (e: Exception) {
            logger.error("Failed to delete documents for visit ${command.visitId}: ${e.message}", e)
        }

        // 5. Hard-delete comments and notes
        try {
            val comments = visitCommentRepository.findByVisitIdOrderByCreatedAtAsc(command.visitId.value)
            visitCommentRepository.deleteAll(comments)
        } catch (e: Exception) {
            logger.error("Failed to delete comments for visit ${command.visitId}: ${e.message}", e)
        }

        // 6. Delete visit entity (cascades to: service items, photos, journal entries)
        visitRepository.delete(visitEntity)

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.VISIT,
            entityId = command.visitId.value.toString(),
            entityDisplayName = "Wizyta #$visitNumber",
            action = AuditAction.VISIT_DELETED,
            changes = listOf(FieldChange("status", visitEntity.status.name, "DELETED"))
        ))
    }
}

data class DeleteVisitCommand(
    val visitId: VisitId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String? = null
)
