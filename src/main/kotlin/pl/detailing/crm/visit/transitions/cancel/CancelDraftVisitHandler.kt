package pl.detailing.crm.visit.transitions.cancel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.protocol.infrastructure.VisitProtocolRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.signing.SignatureRequestLifecycleService
import pl.detailing.crm.visit.infrastructure.S3DamageMapStorageService
import pl.detailing.crm.visit.infrastructure.VisitDocumentRepository
import pl.detailing.crm.visit.infrastructure.VisitJournalEntryRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

/**
 * Handler for cancelling a DRAFT visit.
 *
 * This operation:
 * - Validates that visit is in DRAFT status (only DRAFT visits can be cancelled)
 * - Deletes all associated protocols, documents and journal entries from database
 * - Deletes the visit from database
 * - Deletes protocol/document/damage-map files from S3 (after the DB commit)
 * - Appointment remains in CONFIRMED status (ready to be converted again) - unless it is
 *   the shadow appointment of a walk-in check-in, which is soft-deleted together with the
 *   visit: nobody booked it, so leaving it would put a phantom reservation in the calendar
 *
 * All database deletions happen in ONE real transaction (TransactionTemplate — the
 * body of a `@Transactional suspend` function running on Dispatchers.IO escapes the
 * interceptor-managed transaction, so the template is used deliberately here).
 * Either the whole visit graph is removed or nothing is — no partial cancellation
 * and no FK violation on visit_documents/visit_journal_entries mid-way.
 * S3 cleanup is best-effort and runs only after the transaction has committed,
 * so a rollback never leaves documents without their underlying files.
 *
 * Note: This is a hard delete operation for DRAFT visits.
 * Confirmed visits (IN_PROGRESS and beyond) should use soft delete/rejection instead.
 */
@Service
class CancelDraftVisitHandler(
    private val visitRepository: VisitRepository,
    private val visitProtocolRepository: VisitProtocolRepository,
    private val visitDocumentRepository: VisitDocumentRepository,
    private val visitJournalEntryRepository: VisitJournalEntryRepository,
    private val s3ProtocolStorageService: S3ProtocolStorageService,
    private val s3DamageMapStorageService: S3DamageMapStorageService,
    private val transactionTemplate: TransactionTemplate,
    private val auditService: AuditService,
    private val signatureRequestLifecycleService: SignatureRequestLifecycleService,
    private val appointmentRepository: AppointmentRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private data class DbCancellationResult(
        val visitNumber: String,
        val protocolS3Keys: List<String>,
        val documentS3Keys: List<String>,
        val damageMapS3Key: String?,
        val walkInAppointmentRemoved: Boolean
    )

    suspend fun handle(command: CancelDraftVisitCommand): CancelDraftVisitResult =
        withContext(Dispatchers.IO) {
            // Phase 1: remove the whole visit graph in a single transaction
            val db = transactionTemplate.execute {
                val visitEntity = visitRepository.findByIdAndStudioId(
                    command.visitId.value,
                    command.studioId.value
                ) ?: throw EntityNotFoundException("Wizyta nie została znaleziona")

                // Validate visit is in DRAFT status (check directly on entity to avoid lazy loading issues)
                if (visitEntity.status != VisitStatus.DRAFT) {
                    throw ValidationException(
                        "Anulować można tylko wizyty o statusie DRAFT. Aktualny status: ${visitEntity.status}. " +
                        "Aby anulować potwierdzoną wizytę, użyj procesu odrzucenia."
                    )
                }

                val protocols = visitProtocolRepository.findAllByVisitIdAndStudioId(
                    command.visitId.value,
                    command.studioId.value
                )
                val protocolS3Keys = protocols.flatMap {
                    listOfNotNull(it.filledPdfS3Key, it.signedPdfS3Key, it.signatureImageS3Key)
                }
                visitProtocolRepository.deleteAll(protocols)

                val documents = visitDocumentRepository.findByVisit_IdOrderByUploadedAtDesc(command.visitId.value)
                val documentS3Keys = documents.map { it.fileId }
                visitDocumentRepository.deleteAll(documents)

                val journalEntries = visitJournalEntryRepository.findByVisitId(command.visitId.value)
                visitJournalEntryRepository.deleteAll(journalEntries)

                visitRepository.delete(visitEntity)

                // Rezerwacja z kalendarza zostaje bez zmian - auto można przyjąć od nowa.
                // Rezerwacja-cień walk-inu („Wizyta" w kalendarzu) nie była niczyją
                // rezerwacją: znika razem ze szkicem, w tej samej transakcji, także gdy
                // szkic sprząta StaleDraftVisitCleanupJob.
                val walkInAppointment = appointmentRepository
                    .findByIdAndStudioId(visitEntity.appointmentId, command.studioId.value)
                    ?.takeIf { it.walkIn }
                walkInAppointment?.let {
                    val now = Instant.now()
                    it.deletedAt = now
                    it.updatedAt = now
                    command.userId?.let { userId -> it.updatedBy = userId.value }
                    appointmentRepository.save(it)
                }

                DbCancellationResult(
                    visitNumber = visitEntity.visitNumber,
                    protocolS3Keys = protocolS3Keys,
                    documentS3Keys = documentS3Keys,
                    damageMapS3Key = visitEntity.damageMapFileId,
                    walkInAppointmentRemoved = walkInAppointment != null
                )
            }!!

            // Żądania podpisu protokołów tej wizyty schodzą z tabletu. Inaczej klient
            // dostawał do podpisu dokument wizyty, której już nie ma, a tablet
            // podawał go przed dokumentami nowego przyjęcia (kolejka od najstarszego).
            try {
                signatureRequestLifecycleService.cancelActiveForVisit(
                    command.studioId, command.visitId.value, command.userName ?: "System"
                )
            } catch (e: Exception) {
                // Siatka w kolejce tabletu (withoutOrphaned) i tak je odrzuci.
                logger.error("Failed to cancel signature requests of visit ${command.visitId}: ${e.message}", e)
            }

            // Phase 2 (post-commit, best-effort): remove files from S3
            db.protocolS3Keys.forEach { s3Key ->
                try {
                    s3ProtocolStorageService.deleteFile(s3Key)
                } catch (e: Exception) {
                    logger.error("Failed to delete protocol file from S3: $s3Key: ${e.message}", e)
                }
            }
            db.documentS3Keys.forEach { s3Key ->
                try {
                    s3ProtocolStorageService.deleteFile(s3Key)
                } catch (e: Exception) {
                    logger.error("Failed to delete document file from S3: $s3Key: ${e.message}", e)
                }
            }
            db.damageMapS3Key?.let { s3Key ->
                try {
                    s3DamageMapStorageService.deleteFile(s3Key)
                } catch (e: Exception) {
                    logger.error("Failed to delete damage map from S3: ${e.message}", e)
                }
            }

            // Anulowanie ma dwa źródła: człowieka i zadanie sprzątające
            // (StaleDraftVisitCleanupJob). W dzienniku muszą się różnić — „System"
            // przy wpisie jest dla obsługi informacją, że przyjęcia nikt nie odwołał,
            // tylko wygasło.
            auditService.record(AuditEvent(
                studioId = command.studioId,
                actor = command.userId
                    ?.let { AuditActor.employee(it, command.userName) }
                    ?: AuditActor.system(command.userName ?: "System"),
                module = AuditModule.VISIT,
                entityId = command.visitId.value.toString(),
                entityDisplayName = "Wizyta #${db.visitNumber}",
                action = AuditAction.VISIT_CANCELLED,
                changes = listOf(FieldChange("status", VisitStatus.DRAFT.name, "CANCELLED")),
                metadata = command.reason?.let { mapOf("reason" to it) } ?: emptyMap()
            ))

            CancelDraftVisitResult(
                visitId = command.visitId,
                reservationKept = !db.walkInAppointmentRemoved
            )
        }
}

data class CancelDraftVisitCommand(
    val visitId: VisitId,
    val studioId: StudioId,
    /** `null` = anulowanie systemowe (wygasły szkic), nie działanie pracownika. */
    val userId: UserId? = null,
    val userName: String? = null,
    /** Powód trafiający do metadanych wpisu — wypełniany przez anulowanie automatyczne. */
    val reason: String? = null
)

data class CancelDraftVisitResult(
    val visitId: VisitId,
    /** false = szkic był walk-inem i jego rezerwacja-cień zniknęła razem z nim. */
    val reservationKept: Boolean = true
)
