package pl.detailing.crm.finance.document

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.finance.domain.CashOperationType
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.FinancialDocument
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.infrastructure.CashOperationEntity
import pl.detailing.crm.finance.infrastructure.CashOperationRepository
import pl.detailing.crm.finance.infrastructure.CashRegisterEntity
import pl.detailing.crm.finance.infrastructure.CashRegisterRepository
import pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.shared.FinancialDocumentId
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Command to create a new financial document (receipt, invoice, or other).
 *
 * Monetary fields must satisfy: [totalNet] + [totalVat] == [totalGross].
 * All amounts are in grosz (1/100 PLN).
 */
data class CreateFinancialDocumentCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userDisplayName: String,

    val visitId: VisitId?,

    val documentType: DocumentType,
    val direction: DocumentDirection,
    val paymentMethod: PaymentMethod,

    /** Net amount in grosz. */
    val totalNet: Long,

    /** VAT amount in grosz. */
    val totalVat: Long,

    /** Gross amount in grosz. Must equal totalNet + totalVat. */
    val totalGross: Long,

    val currency: String = "PLN",
    val issueDate: LocalDate,

    /**
     * Payment due date.
     * Required when [paymentMethod] == [PaymentMethod.TRANSFER]; ignored otherwise.
     */
    val dueDate: LocalDate?,

    val description: String?,
    val counterpartyName: String?,
    val counterpartyNip: String?,

    // KSeF placeholders – pass null until KSeF integration is implemented
    val ksefInvoiceId: UUID? = null,
    val ksefNumber: String? = null
)

/**
 * Atomically creates a [FinancialDocument] and, when the payment method is CASH,
 * updates the studio's cash-register balance in the same transaction.
 *
 * ## Business rules applied:
 * - `totalNet + totalVat` must equal `totalGross` (financial integrity)
 * - CASH   → status = PAID, cash register updated immediately
 * - CARD   → status = PAID, no cash register effect
 * - TRANSFER → status = PENDING, dueDate required
 * - Document number generated as `{TYPE_PREFIX}/{YEAR}/{SEQ:04d}` per studio/type/year
 */
@Service
class CreateFinancialDocumentHandler(
    private val documentRepository: FinancialDocumentRepository,
    private val cashRegisterRepository: CashRegisterRepository,
    private val cashOperationRepository: CashOperationRepository,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(CreateFinancialDocumentHandler::class.java)

    @Transactional
    fun handle(command: CreateFinancialDocumentCommand): FinancialDocument {
        validate(command)

        val status = command.paymentMethod.defaultStatus()
        val paidAt = if (status == DocumentStatus.PAID) Instant.now() else null
        val documentNumber = generateDocumentNumber(
            command.studioId.value, command.documentType, command.issueDate
        )

        val entity = FinancialDocumentEntity(
            id               = UUID.randomUUID(),
            studioId         = command.studioId.value,
            visitId          = command.visitId?.value,
            documentNumber   = documentNumber,
            documentType     = command.documentType,
            direction        = command.direction,
            status           = status,
            paymentMethod    = command.paymentMethod,
            totalNet         = command.totalNet,
            totalVat         = command.totalVat,
            totalGross       = command.totalGross,
            currency         = command.currency,
            issueDate        = command.issueDate,
            dueDate          = command.dueDate,
            paidAt           = paidAt,
            description      = command.description,
            counterpartyName = command.counterpartyName,
            counterpartyNip  = command.counterpartyNip,
            ksefInvoiceId    = command.ksefInvoiceId,
            ksefNumber       = command.ksefNumber,
            createdBy        = command.userId.value,
            updatedBy        = command.userId.value
        )

        val saved = documentRepository.save(entity)

        // Update cash register for cash payments settled immediately
        if (command.paymentMethod.affectsCashRegister()) {
            recordCashMovement(command, saved.id, status)
        }

        log.info(
            "Financial document created: studio={} number={} type={} direction={} gross={} method={}",
            command.studioId, documentNumber, command.documentType,
            command.direction, command.totalGross, command.paymentMethod
        )

        auditService.logSync(
            LogAuditCommand(
                studioId           = command.studioId,
                userId             = command.userId,
                userDisplayName    = command.userDisplayName,
                module             = AuditModule.FINANCE,
                entityId           = saved.id.toString(),
                entityDisplayName  = documentNumber,
                action             = AuditAction.DOCUMENT_ISSUED,
                metadata           = mapOf(
                    "documentType"  to command.documentType.name,
                    "direction"     to command.direction.name,
                    "paymentMethod" to command.paymentMethod.name,
                    "totalGross"    to command.totalGross.toString(),
                    "status"        to status.name
                )
            )
        )

        return saved.toDomain()
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun validate(command: CreateFinancialDocumentCommand) {
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
        if (command.currency.isBlank() || command.currency.length != 3) {
            throw ValidationException("Nieprawidłowy kod waluty: '${command.currency}'")
        }
    }

    /**
     * Generates a human-readable document number: `{PREFIX}/{YEAR}/{SEQ:04d}`.
     * Example: `PAR/2024/0001`, `FAK/2024/0012`.
     *
     * Sequential number is derived from the count of existing documents of the
     * same type/year for this studio.  Under normal single-user CRM usage this
     * is deterministic; for very high concurrency a unique constraint on
     * (studio_id, document_number) would prevent duplicates.
     */
    private fun generateDocumentNumber(
        studioId: UUID,
        type: DocumentType,
        issueDate: LocalDate
    ): String {
        val year     = issueDate.year
        val yearStart = LocalDate.of(year, 1, 1)
        val yearEnd   = LocalDate.of(year + 1, 1, 1)
        val count = documentRepository.countByStudioTypeAndYear(studioId, type, yearStart, yearEnd)
        val seq   = (count + 1).toString().padStart(4, '0')
        return "${type.prefix}/$year/$seq"
    }

    /**
     * Updates the cash-register balance and appends an immutable operation record.
     * Acquires a pessimistic write lock on the cash register to prevent concurrent
     * balance corruption.
     */
    private fun recordCashMovement(
        command: CreateFinancialDocumentCommand,
        documentId: UUID,
        documentStatus: DocumentStatus
    ) {
        // Only record movement when the document is PAID (should always be true for cash)
        if (documentStatus != DocumentStatus.PAID) return

        val cashRegister = getOrCreateCashRegister(command.studioId.value)
        val balanceBefore = cashRegister.balance

        val changeAmount = when (command.direction) {
            DocumentDirection.INCOME  ->  command.totalGross  // + into the register
            DocumentDirection.EXPENSE -> -command.totalGross  // - out of the register
        }

        val newBalance = balanceBefore + changeAmount
        if (newBalance < 0) {
            log.warn(
                "Cash register balance would go negative for studio={}: before={} change={}",
                command.studioId, balanceBefore, changeAmount
            )
            // We allow it – the studio may track corrections afterward.
            // Throw ValidationException here if a strict non-negative policy is required.
        }

        cashRegister.balance   = newBalance
        cashRegister.updatedAt = Instant.now()
        cashRegisterRepository.save(cashRegister)

        val operationType = when (command.direction) {
            DocumentDirection.INCOME  -> CashOperationType.PAYMENT_IN
            DocumentDirection.EXPENSE -> CashOperationType.PAYMENT_OUT
        }

        cashOperationRepository.save(
            CashOperationEntity(
                id                  = UUID.randomUUID(),
                studioId            = command.studioId.value,
                cashRegisterId      = cashRegister.id,
                amount              = changeAmount,
                balanceBefore       = balanceBefore,
                balanceAfter        = newBalance,
                operationType       = operationType,
                comment             = null,
                financialDocumentId = documentId,
                createdBy           = command.userId.value
            )
        )

        log.debug(
            "Cash register updated: studio={} before={} change={} after={}",
            command.studioId, balanceBefore, changeAmount, newBalance
        )
    }

    private fun getOrCreateCashRegister(studioId: UUID): CashRegisterEntity {
        return cashRegisterRepository.findByStudioIdForUpdate(studioId)
            ?: cashRegisterRepository.save(
                CashRegisterEntity(studioId = studioId, balance = 0L)
            )
    }
}
