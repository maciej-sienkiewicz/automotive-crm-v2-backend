package pl.detailing.crm.visit.transitions.cancel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.protocol.infrastructure.VisitProtocolRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.DocumentService
import pl.detailing.crm.visit.infrastructure.S3DamageMapStorageService
import pl.detailing.crm.visit.infrastructure.VisitRepository

/**
 * Handler for cancelling a DRAFT visit.
 *
 * This operation:
 * - Validates that visit is in DRAFT status (only DRAFT visits can be cancelled)
 * - Deletes all associated protocols from database and S3
 * - Deletes damage map from S3 if exists
 * - Deletes the visit from database
 * - Appointment remains in CONFIRMED status (ready to be converted again)
 *
 * Note: This is a hard delete operation for DRAFT visits.
 * Confirmed visits (IN_PROGRESS and beyond) should use soft delete/rejection instead.
 */
@Service
class CancelDraftVisitHandler(
    private val visitRepository: VisitRepository,
    private val visitProtocolRepository: VisitProtocolRepository,
    private val s3ProtocolStorageService: S3ProtocolStorageService,
    private val s3DamageMapStorageService: S3DamageMapStorageService,
    private val documentService: DocumentService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(command: CancelDraftVisitCommand): CancelDraftVisitResult =
        withContext(Dispatchers.IO) {
            // Load visit
            val visitEntity = visitRepository.findByIdAndStudioId(
                command.visitId.value,
                command.studioId.value
            ) ?: throw EntityNotFoundException("Visit not found")

            val visit = visitEntity.toDomain()

            // Validate visit is in DRAFT status
            if (visit.status != VisitStatus.DRAFT) {
                throw ValidationException(
                    "Only DRAFT visits can be cancelled. Current status: ${visit.status}. " +
                    "To cancel a confirmed visit, use the rejection flow instead."
                )
            }

            // Delete all protocols and their files from S3
            val protocols = visitProtocolRepository.findAllByVisitIdAndStudioId(
                command.visitId.value,
                command.studioId.value
            )

            protocols.forEach { protocol ->
                try {
                    // Delete filled PDF from S3
                    protocol.filledPdfS3Key?.let { s3Key ->
                        s3ProtocolStorageService.deleteFile(s3Key)
                    }

                    // Delete signed PDF from S3 (if exists)
                    protocol.signedPdfS3Key?.let { s3Key ->
                        s3ProtocolStorageService.deleteFile(s3Key)
                    }

                    // Delete signature image from S3 (if exists)
                    protocol.signatureImageS3Key?.let { s3Key ->
                        s3ProtocolStorageService.deleteFile(s3Key)
                    }
                } catch (e: Exception) {
                    logger.error("Failed to delete protocol files from S3 for protocol ${protocol.id}: ${e.message}", e)
                    // Continue with deletion even if S3 cleanup fails
                }

                // Delete protocol from database
                visitProtocolRepository.delete(protocol)
            }

            // Delete damage map from S3 if exists
            visit.damageMapFileId?.let { s3Key ->
                try {
                    s3DamageMapStorageService.deleteFile(s3Key)
                } catch (e: Exception) {
                    logger.error("Failed to delete damage map from S3: ${e.message}", e)
                    // Continue with deletion even if S3 cleanup fails
                }
            }

            // Delete all documents associated with the visit
            try {
                documentService.deleteAllDocumentsForVisit(command.visitId.value)
            } catch (e: Exception) {
                logger.error("Failed to delete documents for visit ${command.visitId}: ${e.message}", e)
                // Continue with deletion
            }

            // Delete visit from database
            visitRepository.delete(visitEntity)

            // Note: Appointment remains in CONFIRMED status and is NOT modified
            // This allows the appointment to be converted to a new visit later

            CancelDraftVisitResult(visitId = command.visitId)
        }
}

data class CancelDraftVisitCommand(
    val visitId: VisitId,
    val studioId: StudioId,
    val userId: UserId
)

data class CancelDraftVisitResult(
    val visitId: VisitId
)
