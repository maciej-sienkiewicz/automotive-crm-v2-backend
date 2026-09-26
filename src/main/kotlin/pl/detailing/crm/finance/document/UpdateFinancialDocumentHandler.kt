package pl.detailing.crm.finance.document

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditAmount
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.AuditValueType
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.audit.domain.auditMoney
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentSource
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.FinancialDocument
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Nowa treść dokumentu. Kwoty w groszach; [totalGross] to kwota ustalona przez
 * człowieka i jest zapisywana bez przeliczania (CLAUDE.md §1).
 */
data class UpdateFinancialDocumentCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userDisplayName: String,
    val documentId: UUID,
    /** Typ, który widzi formularz — musi zgadzać się z zapisanym; null = bez sprawdzania. */
    val documentType: DocumentType?,
    val paymentMethod: PaymentMethod,
    val totalNet: Long,
    val totalVat: Long,
    val totalGross: Long,
    val issueDate: LocalDate,
    val dueDate: LocalDate?,
    val description: String?,
    val counterpartyName: String?,
    val counterpartyNip: String?
)

/**
 * Edycja dokumentu finansowego (PUT /api/v1/finance/documents/{id}).
 *
 * Okno „Edytuj dokument" istniało we froncie od dawna, ale backend nie miał tego
 * endpointu — każdy zapis kończył się błędem. Reguły:
 *
 *  - **Typ dokumentu się nie zmienia.** Numer niesie prefiks typu (PAR/…, DOK/…) i jest
 *    już wydany; paragon przerobiony na „inny" dalej nosiłby numer paragonu.
 *  - **Dokument powiązany z fakturą KSeF** — tylko opis. Kwoty, forma płatności i daty
 *    faktury przyjętej w KSeF zmienia się fakturą korygującą, nie tutaj.
 *  - **Dokument z wydania pojazdu** — kwoty bez zmian. Muszą zgadzać się z kwotą wizyty,
 *    z której liczą się statystyki; formę płatności wolno poprawić.
 *  - **Kasa idzie za dokumentem.** Zmiana kwoty albo formy płatności gotówkowej dopisuje
 *    korektę kasy równą RÓŻNICY skutku przed i po — tylko różnicy, więc dokument sprzed
 *    modułu kasy (bez wpłaty w historii) nie dostaje nagle całej kwoty.
 *  - Każda zmiana trafia do Aktywności z wartościami przed i po.
 */
@Service
class UpdateFinancialDocumentHandler(
    private val documentRepository: FinancialDocumentRepository,
    private val cashCorrections: DocumentCashCorrections,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(UpdateFinancialDocumentHandler::class.java)

    @Transactional
    fun handle(command: UpdateFinancialDocumentCommand): FinancialDocument {
        val document = documentRepository.findByIdAndStudioId(command.documentId, command.studioId.value)
            ?: throw EntityNotFoundException("Dokument finansowy ${command.documentId} nie istnieje")

        validate(command, document)

        val before = Snapshot.of(document)
        val cashBefore = cashEffect(document.direction, before.paymentMethod, before.status, before.totalGross)

        val methodChanged = command.paymentMethod != document.paymentMethod
        // Nowa forma płatności ustala status od nowa (gotówka i karta = opłacony, przelew =
        // czeka); ta sama forma zostawia status, który ktoś mógł już ręcznie zmienić.
        val newStatus = if (methodChanged) command.paymentMethod.defaultStatus() else document.status

        document.paymentMethod    = command.paymentMethod
        document.status           = newStatus
        document.paidAt           = when {
            newStatus != DocumentStatus.PAID -> null
            document.paidAt == null          -> Instant.now()
            else                             -> document.paidAt
        }
        document.totalNet         = command.totalNet
        document.totalVat         = command.totalVat
        document.totalGross       = command.totalGross
        document.issueDate        = command.issueDate
        document.dueDate          = command.dueDate
        document.description      = command.description?.trim()?.ifBlank { null }
        document.counterpartyName = command.counterpartyName?.trim()?.ifBlank { null }
        document.counterpartyNip  = command.counterpartyNip?.trim()?.ifBlank { null }

        val after = Snapshot.of(document)
        val changes = before.changesTo(after)
        if (changes.isEmpty()) return document.toDomain()
        document.updatedBy = command.userId.value
        document.updatedAt = Instant.now()

        val saved = documentRepository.save(document)

        val cashDelta = cashEffect(document.direction, after.paymentMethod, after.status, after.totalGross) - cashBefore
        cashCorrections.record(
            command.studioId.value, command.userId.value, document.id, cashDelta,
            "Zmiana dokumentu ${document.documentNumber}"
        )

        auditService.logSync(
            LogAuditCommand(
                studioId          = command.studioId,
                userId            = command.userId,
                userDisplayName   = command.userDisplayName,
                module            = AuditModule.FINANCE,
                entityId          = document.id.toString(),
                entityDisplayName = document.documentNumber,
                action            = AuditAction.DOCUMENT_UPDATED,
                changes           = changes,
                amount            = AuditAmount.ofGrosz(document.totalGross),
                metadata          = mapOf("cashCorrection" to cashDelta.toString())
            )
        )
        log.info(
            "Dokument zmieniony: studio={} number={} pola={} korekta kasy={}",
            command.studioId, document.documentNumber, changes.map { it.field }, cashDelta
        )
        return saved.toDomain()
    }

    private fun validate(command: UpdateFinancialDocumentCommand, document: FinancialDocumentEntity) {
        requireNotPartOfSettlementCorrection(document)
        if (command.documentType != null && command.documentType != document.documentType) {
            throw ValidationException(
                "Typu dokumentu nie można zmienić — numer ${document.documentNumber} należy do serii " +
                    "${document.documentType.displayName.lowercase()}. Usuń dokument i wystaw nowy."
            )
        }
        if (command.totalNet < 0 || command.totalVat < 0 || command.totalGross < 0) {
            throw ValidationException("Kwoty dokumentu finansowego nie mogą być ujemne")
        }
        if (command.totalNet + command.totalVat != command.totalGross) {
            throw ValidationException(
                "Niespójność kwot: netto (${command.totalNet}) + VAT (${command.totalVat}) ≠ brutto (${command.totalGross})"
            )
        }
        if (command.paymentMethod == PaymentMethod.TRANSFER && command.dueDate == null) {
            throw ValidationException("Termin płatności jest wymagany dla płatności przelewem")
        }

        val amountsChanged = command.totalNet != document.totalNet ||
            command.totalVat != document.totalVat ||
            command.totalGross != document.totalGross

        if (document.ksefRevenueInvoiceId != null) {
            val settlementChanged = amountsChanged ||
                command.paymentMethod != document.paymentMethod ||
                command.issueDate != document.issueDate ||
                command.dueDate != document.dueDate ||
                command.counterpartyName?.trim()?.ifBlank { null } != document.counterpartyName ||
                command.counterpartyNip?.trim()?.ifBlank { null } != document.counterpartyNip
            if (settlementChanged) {
                throw ValidationException(
                    "Ten dokument należy do faktury KSeF. Kwoty, płatność, daty i nabywcę faktury " +
                        "zmienia się fakturą korygującą — tutaj można zmienić tylko opis."
                )
            }
        }
        if (document.source == DocumentSource.VISIT && amountsChanged) {
            throw ValidationException(
                "Kwoty dokumentu wystawionego przy wydaniu pojazdu muszą zgadzać się z kwotą wizyty — " +
                    "tutaj można zmienić formę płatności, daty i opis."
            )
        }
    }

    /** Ile dokument wnosi do kasy: tylko opłacona gotówka, przychód na plus, koszt na minus. */
    private fun cashEffect(direction: DocumentDirection, method: PaymentMethod, status: DocumentStatus, gross: Long): Long {
        if (!method.affectsCashRegister() || status != DocumentStatus.PAID) return 0
        return if (direction == DocumentDirection.INCOME) gross else -gross
    }

    private data class Snapshot(
        val paymentMethod: PaymentMethod,
        val status: DocumentStatus,
        val totalNet: Long,
        val totalVat: Long,
        val totalGross: Long,
        val issueDate: LocalDate,
        val dueDate: LocalDate?,
        val description: String?,
        val counterpartyName: String?,
        val counterpartyNip: String?
    ) {
        fun changesTo(other: Snapshot): List<FieldChange> = buildList {
            if (paymentMethod != other.paymentMethod) {
                add(FieldChange("paymentMethod", paymentMethod.displayName, other.paymentMethod.displayName))
            }
            if (status != other.status) add(FieldChange("status", status.displayName, other.status.displayName))
            if (totalNet != other.totalNet) {
                add(FieldChange("totalNet", auditMoney(totalNet), auditMoney(other.totalNet), AuditValueType.MONEY))
            }
            if (totalVat != other.totalVat) {
                add(FieldChange("totalVat", auditMoney(totalVat), auditMoney(other.totalVat), AuditValueType.MONEY))
            }
            if (totalGross != other.totalGross) {
                add(FieldChange("totalGross", auditMoney(totalGross), auditMoney(other.totalGross), AuditValueType.MONEY))
            }
            if (issueDate != other.issueDate) add(FieldChange("issueDate", issueDate.toString(), other.issueDate.toString()))
            if (dueDate != other.dueDate) add(FieldChange("dueDate", dueDate?.toString(), other.dueDate?.toString()))
            if (description != other.description) add(FieldChange("description", description, other.description))
            if (counterpartyName != other.counterpartyName) {
                add(FieldChange("counterpartyName", counterpartyName, other.counterpartyName))
            }
            if (counterpartyNip != other.counterpartyNip) {
                add(FieldChange("counterpartyNip", counterpartyNip, other.counterpartyNip))
            }
        }

        companion object {
            fun of(d: FinancialDocumentEntity) = Snapshot(
                d.paymentMethod, d.status, d.totalNet, d.totalVat, d.totalGross,
                d.issueDate, d.dueDate, d.description, d.counterpartyName, d.counterpartyNip
            )
        }
    }
}
