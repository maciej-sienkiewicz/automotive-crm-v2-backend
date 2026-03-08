package pl.detailing.crm.finance.document

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.FinancialDocument
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.invoicing.InvoicingFacade
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.FinancialDocumentId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.ZoneOffset

data class UpdateDocumentStatusCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userDisplayName: String,
    val documentId: FinancialDocumentId,
    val newStatus: DocumentStatus
)

/**
 * Transitions a financial document to a new status.
 *
 * Allowed transitions:
 * - PENDING  → PAID     (user confirms receipt of bank transfer)
 * - PENDING  → OVERDUE  (manual mark or triggered by scheduler)
 * - OVERDUE  → PAID     (late payment finally received)
 *
 * PAID documents cannot be un-paid; use soft-delete to void a paid document.
 *
 * When the document is linked to an external provider (e.g. inFakt) and the new status
 * is PAID, the handler also notifies the provider to mark the invoice as paid there.
 * Provider call failures are logged as warnings but do NOT roll back the local status change.
 */
@Service
class UpdateDocumentStatusHandler(
    private val documentRepository: FinancialDocumentRepository,
    private val auditService: AuditService,
    private val invoicingFacade: InvoicingFacade
) {
    private val log = LoggerFactory.getLogger(UpdateDocumentStatusHandler::class.java)

    @Transactional
    fun handle(command: UpdateDocumentStatusCommand): FinancialDocument {
        val entity = documentRepository.findByIdAndStudioId(
            command.documentId.value, command.studioId.value
        ) ?: throw EntityNotFoundException(
            "Dokument finansowy ${command.documentId} nie istnieje lub należy do innego studia"
        )

        val oldStatus = entity.status
        validateTransition(oldStatus, command.newStatus, command.documentId)

        entity.status    = command.newStatus
        entity.paidAt    = if (command.newStatus == DocumentStatus.PAID) Instant.now() else entity.paidAt
        entity.updatedBy = command.userId.value
        entity.updatedAt = Instant.now()

        val saved = documentRepository.save(entity)

        log.info(
            "Document status updated: studio={} document={} {} → {}",
            command.studioId, command.documentId, oldStatus, command.newStatus
        )

        // Notify the external provider when marking as paid
        if (command.newStatus == DocumentStatus.PAID) {
            val provider   = entity.provider
            val externalId = entity.externalId
            if (provider != null && externalId != null) {
                val paidDate = entity.paidAt?.atOffset(ZoneOffset.UTC)?.toLocalDate()?.toString()
                try {
                    invoicingFacade.markInvoiceAsPaid(command.studioId, provider, externalId, paidDate)
                    log.info(
                        "[Invoice] Marked externalId={} as paid in provider={}",
                        externalId, provider
                    )
                } catch (ex: Exception) {
                    log.warn(
                        "[Invoice] Failed to mark externalId={} as paid in provider={}: {}",
                        externalId, provider, ex.message
                    )
                }
            }
        }

        auditService.logSync(
            LogAuditCommand(
                studioId          = command.studioId,
                userId            = command.userId,
                userDisplayName   = command.userDisplayName,
                module            = AuditModule.FINANCE,
                entityId          = command.documentId.toString(),
                entityDisplayName = entity.documentNumber,
                action            = AuditAction.DOCUMENT_STATUS_CHANGED,
                changes           = listOf(
                    FieldChange("status", oldStatus.name, command.newStatus.name)
                )
            )
        )

        return saved.toDomain()
    }

    private fun validateTransition(
        from: DocumentStatus,
        to: DocumentStatus,
        documentId: FinancialDocumentId
    ) {
        if (from == to) {
            throw ValidationException("Dokument $documentId ma już status ${from.displayName}")
        }
        if (from == DocumentStatus.PAID) {
            throw ValidationException(
                "Nie można zmienić statusu opłaconego dokumentu $documentId. " +
                "Aby anulować, użyj operacji usunięcia dokumentu."
            )
        }
    }
}
