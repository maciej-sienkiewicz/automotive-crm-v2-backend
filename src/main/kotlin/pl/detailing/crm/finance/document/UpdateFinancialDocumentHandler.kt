package pl.detailing.crm.finance.document

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.finance.domain.CashOperationType
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentSource
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.FinancialDocument
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.infrastructure.CashOperationEntity
import pl.detailing.crm.finance.infrastructure.CashOperationRepository
import pl.detailing.crm.finance.infrastructure.CashRegisterRepository
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.FinancialDocumentId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Command to update an existing MANUAL financial document.
 *
 * Only documents with [DocumentSource.MANUAL] may be edited; VISIT-sourced
 * documents are immutable via this flow (their data is owned by the visit).
 *
 * Direction is intentionally excluded — changing INCOME to EXPENSE (or vice
 * versa) would require a full cash-register reversal and is handled by
 * delete-and-recreate instead.
 */
data class UpdateFinancialDocumentCommand(
    val studioId:        StudioId,
    val userId:          UserId,
    val userDisplayName: String,
    val documentId:      UUID,
    val documentType:    DocumentType,
    val paymentMethod:   PaymentMethod,
    val totalNet:        Long,
    val totalVat:        Long,
    val totalGross:      Long,
    val issueDate:       LocalDate,
    val dueDate:         LocalDate?,
    val description:     String?,
    val counterpartyName: String?,
    val counterpartyNip:  String?
)

/**
 * Updates a MANUAL [FinancialDocument] and reconciles the cash register when
 * the payment method or gross amount changes.
 *
 * ## Cash reconciliation logic
 * The handler computes the delta between the old cash effect (sum of
 * existing [CashOperationEntity] records linked to this document) and the
 * new cash effect (derived from the incoming command), then creates a
 * single correction operation if a delta exists.
 *
 * - No change in CASH status or amount → no cash operation
 * - CASH amount changed → correction entry for the delta
 * - Switched from CASH to non-CASH → full reversal entry
 * - Switched from non-CASH to CASH → new cash entry
 */
@Service
class UpdateFinancialDocumentHandler(
    private val documentRepository:    FinancialDocumentRepository,
    private val cashRegisterRepository: CashRegisterRepository,
    private val cashOperationRepository: CashOperationRepository,
    private val auditService:           AuditService
) {
    private val log = LoggerFactory.getLogger(UpdateFinancialDocumentHandler::class.java)

    @Transactional
    fun handle(command: UpdateFinancialDocumentCommand): FinancialDocument {
        validate(command)

        val entity = documentRepository.findByIdAndStudioId(command.documentId, command.studioId.value)
            ?: throw EntityNotFoundException("Dokument finansowy ${command.documentId} nie istnieje")

        if (entity.deletedAt != null) {
            throw ValidationException("Nie można edytować usuniętego dokumentu finansowego")
        }
        if (entity.source != DocumentSource.MANUAL) {
            throw ValidationException("Edytować można tylko dokumenty wprowadzone ręcznie (source=MANUAL)")
        }

        val changes = collectChanges(entity, command)

        reconcileCashRegister(entity, command)

        entity.documentType    = command.documentType
        entity.paymentMethod   = command.paymentMethod
        entity.totalNet        = command.totalNet
        entity.totalVat        = command.totalVat
        entity.totalGross      = command.totalGross
        entity.issueDate       = command.issueDate
        entity.dueDate         = command.dueDate
        entity.description     = command.description
        entity.counterpartyName = command.counterpartyName
        entity.counterpartyNip  = command.counterpartyNip
        entity.updatedBy       = command.userId.value
        entity.updatedAt       = Instant.now()

        // When switching to CASH on an already-paid document, stamp paidAt if missing.
        if (command.paymentMethod.affectsCashRegister() &&
            entity.status == DocumentStatus.PAID &&
            entity.paidAt == null) {
            entity.paidAt = Instant.now()
        }

        val saved = documentRepository.save(entity)

        log.info(
            "Financial document updated: studio={} id={} number={} fields={}",
            command.studioId, command.documentId, entity.documentNumber,
            changes.joinToString { it.field }
        )

        if (changes.isNotEmpty()) {
            auditService.logSync(
                LogAuditCommand(
                    studioId          = command.studioId,
                    userId            = command.userId,
                    userDisplayName   = command.userDisplayName,
                    module            = AuditModule.FINANCE,
                    entityId          = command.documentId.toString(),
                    entityDisplayName = entity.documentNumber,
                    action            = AuditAction.UPDATE,
                    changes           = changes
                )
            )
        }

        return saved.toDomain()
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun validate(command: UpdateFinancialDocumentCommand) {
        if (command.totalNet < 0 || command.totalVat < 0 || command.totalGross < 0) {
            throw ValidationException("Kwoty dokumentu finansowego nie mogą być ujemne")
        }
        if (command.totalNet + command.totalVat != command.totalGross) {
            throw ValidationException(
                "Niespójność kwot: netto (${command.totalNet}) + VAT (${command.totalVat}) ≠ brutto (${command.totalGross})"
            )
        }
        if (command.paymentMethod == PaymentMethod.TRANSFER && command.dueDate == null) {
            throw ValidationException("Data płatności (dueDate) jest wymagana dla płatności przelewem")
        }
        if (command.paymentMethod == PaymentMethod.TRANSFER &&
            command.dueDate != null && command.dueDate.isBefore(command.issueDate)) {
            throw ValidationException("Data płatności nie może być wcześniejsza niż data wystawienia dokumentu")
        }
    }

    /**
     * Determines the net delta between the old and new cash effect and, if
     * non-zero, creates a correction [CashOperationEntity] and updates the
     * cash register balance.
     */
    private fun reconcileCashRegister(
        entity:  pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity,
        command: UpdateFinancialDocumentCommand
    ) {
        val existingOps   = cashOperationRepository.findByDocumentId(command.studioId.value, command.documentId)
        val oldCashEffect = existingOps.sumOf { it.amount }

        val newCashEffect: Long = if (command.paymentMethod.affectsCashRegister() &&
            entity.status == DocumentStatus.PAID) {
            when (entity.direction) {
                DocumentDirection.INCOME  ->  command.totalGross
                DocumentDirection.EXPENSE -> -command.totalGross
            }
        } else 0L

        val delta = newCashEffect - oldCashEffect
        if (delta == 0L) return

        val cashRegister = cashRegisterRepository.findByStudioIdForUpdate(command.studioId.value)
            ?: throw EntityNotFoundException("Kasa dla studia ${command.studioId} nie istnieje")

        val balanceBefore  = cashRegister.balance
        val newBalance     = balanceBefore + delta
        cashRegister.balance   = newBalance
        cashRegister.updatedAt = Instant.now()
        cashRegisterRepository.save(cashRegister)

        val operationType = if (delta > 0) CashOperationType.PAYMENT_IN else CashOperationType.PAYMENT_OUT

        cashOperationRepository.save(
            CashOperationEntity(
                id                  = UUID.randomUUID(),
                studioId            = command.studioId.value,
                cashRegisterId      = cashRegister.id,
                amount              = delta,
                balanceBefore       = balanceBefore,
                balanceAfter        = newBalance,
                operationType       = operationType,
                comment             = "Korekta: edycja dokumentu ${entity.documentNumber}",
                financialDocumentId = command.documentId,
                createdBy           = command.userId.value
            )
        )

        log.debug(
            "Cash register corrected: studio={} doc={} delta={} before={} after={}",
            command.studioId, command.documentId, delta, balanceBefore, newBalance
        )
    }

    private fun collectChanges(
        entity:  pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity,
        command: UpdateFinancialDocumentCommand
    ): List<FieldChange> = buildList {
        if (entity.documentType    != command.documentType)    add(FieldChange("documentType",    entity.documentType.name,         command.documentType.name))
        if (entity.paymentMethod   != command.paymentMethod)   add(FieldChange("paymentMethod",   entity.paymentMethod.name,        command.paymentMethod.name))
        if (entity.totalNet        != command.totalNet)        add(FieldChange("totalNet",        entity.totalNet.toString(),       command.totalNet.toString()))
        if (entity.totalVat        != command.totalVat)        add(FieldChange("totalVat",        entity.totalVat.toString(),       command.totalVat.toString()))
        if (entity.totalGross      != command.totalGross)      add(FieldChange("totalGross",      entity.totalGross.toString(),     command.totalGross.toString()))
        if (entity.issueDate       != command.issueDate)       add(FieldChange("issueDate",       entity.issueDate.toString(),      command.issueDate.toString()))
        if (entity.dueDate         != command.dueDate)         add(FieldChange("dueDate",         entity.dueDate?.toString(),       command.dueDate?.toString()))
        if (entity.description     != command.description)     add(FieldChange("description",     entity.description,               command.description))
        if (entity.counterpartyName != command.counterpartyName) add(FieldChange("counterpartyName", entity.counterpartyName,      command.counterpartyName))
        if (entity.counterpartyNip  != command.counterpartyNip)  add(FieldChange("counterpartyNip",  entity.counterpartyNip,       command.counterpartyNip))
    }
}
