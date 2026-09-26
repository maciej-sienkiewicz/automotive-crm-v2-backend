package pl.detailing.crm.finance.document

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditAmount
import pl.detailing.crm.audit.domain.AuditContext
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.AuditValueType
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.audit.domain.auditMoney
import pl.detailing.crm.finance.domain.CashOperationType
import pl.detailing.crm.finance.domain.FinancialDocument
import pl.detailing.crm.finance.infrastructure.CashOperationRepository
import pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.infrastructure.auditDisplayName
import java.time.Instant
import java.util.UUID

data class RemoveFinancialDocumentCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userDisplayName: String,
    val documentId: UUID
)

/**
 * Usunięcie (soft-delete) i przywrócenie dokumentu finansowego razem z jego skutkiem w kasie.
 *
 * Dwa błędy, które to zamyka:
 *  - usunięcie paragonu gotówkowego nie cofało wpłaty. Saldo kasy to zapisana liczba,
 *    nie suma wpisów, więc każde usunięcie trwale je rozjeżdżało;
 *  - usunięcie nie trafiało do historii zmian. W Aktywności było widać, że dokument
 *    wystawiono, ale nie to, że ktoś go usunął.
 *
 * Kasa jest dziennikiem dopisywanym: nic nie jest kasowane ani nadpisywane. Usunięcie
 * dopisuje wpis [CashOperationType.DOCUMENT_CORRECTION] ([DocumentCashCorrections]) równy MINUS temu, co dokument
 * faktycznie wniósł do kasy (suma jego wpisów, nie kwota dokumentu - dokument sprzed
 * modułu kasy nie ma wpisu i nie ma czego cofać). Przywrócenie cofa dokładnie te korekty.
 * Dzięki temu usunięcie i przywrócenie w dowolnej kolejności wracają do tego samego salda.
 */
@Service
class FinancialDocumentRemovalHandler(
    private val documentRepository: FinancialDocumentRepository,
    private val cashOperationRepository: CashOperationRepository,
    private val cashCorrections: DocumentCashCorrections,
    private val visitRepository: VisitRepository,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(FinancialDocumentRemovalHandler::class.java)

    @Transactional
    fun delete(command: RemoveFinancialDocumentCommand) {
        val document = documentRepository.findByIdAndStudioId(command.documentId, command.studioId.value)
            ?: throw EntityNotFoundException("Dokument finansowy ${command.documentId} nie istnieje")

        val now = Instant.now()
        document.deletedAt = now
        document.updatedBy = command.userId.value
        document.updatedAt = now
        documentRepository.save(document)

        val operations = cashOperationRepository.findByDocumentId(command.studioId.value, document.id)
        val cashCorrection = -operations.sumOf { it.amount }
        if (cashCorrection != 0L) {
            recordCorrection(command, document, cashCorrection, "Usunięcie dokumentu ${document.documentNumber}")
        }

        audit(command, document, AuditAction.DOCUMENT_DELETED, cashCorrection)
        log.info(
            "Dokument usunięty: studio={} number={} gross={} korekta kasy={}",
            command.studioId, document.documentNumber, document.totalGross, cashCorrection
        )
    }

    @Transactional
    fun restore(command: RemoveFinancialDocumentCommand): FinancialDocument {
        val document = documentRepository.findByIdAndStudioIdIncludingDeleted(command.documentId, command.studioId.value)
            ?: throw EntityNotFoundException("Dokument finansowy ${command.documentId} nie istnieje")
        if (document.deletedAt == null) throw ValidationException("Dokument ${command.documentId} nie jest usunięty")

        document.deletedAt = null
        document.updatedBy = command.userId.value
        document.updatedAt = Instant.now()
        val saved = documentRepository.save(document)

        val corrections = cashOperationRepository.findByDocumentId(command.studioId.value, document.id)
            .filter { it.operationType == CashOperationType.DOCUMENT_CORRECTION }
        val cashCorrection = -corrections.sumOf { it.amount }
        if (cashCorrection != 0L) {
            recordCorrection(command, document, cashCorrection, "Przywrócenie dokumentu ${document.documentNumber}")
        }

        audit(command, document, AuditAction.DOCUMENT_RESTORED, cashCorrection)
        return saved.toDomain()
    }

    private fun recordCorrection(
        command: RemoveFinancialDocumentCommand,
        document: FinancialDocumentEntity,
        amount: Long,
        comment: String
    ) = cashCorrections.record(command.studioId.value, command.userId.value, document.id, amount, comment)

    private fun audit(
        command: RemoveFinancialDocumentCommand,
        document: FinancialDocumentEntity,
        action: AuditAction,
        cashCorrection: Long
    ) {
        val visitId = document.visitId?.let { VisitId(it) }
        val visitName = visitId?.let { visitRepository.findByIdAndStudioId(it.value, command.studioId.value)?.auditDisplayName }
        val changes = buildList {
            val deleted = action == AuditAction.DOCUMENT_DELETED
            add(FieldChange("documentNumber", document.documentNumber.takeIf { deleted }, document.documentNumber.takeUnless { deleted }))
            add(FieldChange("totalGross", null, auditMoney(document.totalGross), AuditValueType.MONEY))
            if (cashCorrection != 0L) {
                add(FieldChange("cashCorrection", null, auditMoney(cashCorrection), AuditValueType.MONEY))
            }
        }
        auditService.logSync(
            LogAuditCommand(
                studioId          = command.studioId,
                userId            = command.userId,
                userDisplayName   = command.userDisplayName,
                module            = AuditModule.FINANCE,
                entityId          = document.id.toString(),
                entityDisplayName = visitName ?: document.documentNumber,
                action            = action,
                changes           = changes,
                amount            = AuditAmount.ofGrosz(document.totalGross),
                context           = AuditContext(visitId = visitId, visitName = visitName),
                metadata          = mapOf(
                    "documentType"   to document.documentType.name,
                    "paymentMethod"  to document.paymentMethod.name,
                    "cashCorrection" to cashCorrection.toString()
                )
            )
        )
    }
}
