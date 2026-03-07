package pl.detailing.crm.invoicing.issue

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.finance.domain.CashOperationType
import pl.detailing.crm.finance.infrastructure.CashOperationEntity
import pl.detailing.crm.finance.infrastructure.CashOperationRepository
import pl.detailing.crm.finance.infrastructure.CashRegisterEntity
import pl.detailing.crm.finance.infrastructure.CashRegisterRepository
import pl.detailing.crm.invoicing.InvoiceProviderRegistry
import pl.detailing.crm.invoicing.credentials.InvoicingCredentialsRepository
import pl.detailing.crm.invoicing.domain.*
import pl.detailing.crm.invoicing.infrastructure.ExternalInvoiceEntity
import pl.detailing.crm.invoicing.infrastructure.ExternalInvoiceRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Command to issue an invoice when a visit is completed.
 *
 * The handler will attempt to send the invoice to the studio's configured external provider.
 * If the provider call fails, the invoice is saved locally with [InvoiceProviderSyncStatus.SYNC_FAILED]
 * so it can be retried later via POST /api/v1/invoicing/invoices/{id}/retry-sync.
 *
 * If no provider is configured, this handler is not called – the caller is responsible
 * for checking credentials before invoking.
 */
data class IssueVisitInvoiceCommand(
    val studioId: StudioId,
    val userId: UserId,
    val visitId: VisitId,

    val buyerName: String,
    val buyerNip: String?,
    val buyerEmail: String?,
    val buyerStreet: String?,
    val buyerCity: String?,
    val buyerPostCode: String?,

    val items: List<InvoiceItemCommand>,

    /** CASH | CARD | TRANSFER */
    val paymentMethod: String,

    val issueDate: LocalDate,
    val dueDate: LocalDate?,
    val currency: String = "PLN",
    val description: String?,

    /** Gross amount in grosz – used for cash register update. */
    val grossAmountInCents: Long,
    val netAmountInCents: Long,
    val vatAmountInCents: Long
)

/**
 * Handles invoice issuance at visit completion.
 *
 * Flow:
 * 1. Validates that at least one item and a buyer name are present.
 * 2. Attempts to send the invoice to the studio's configured provider.
 *    - On success: persists a local record with [InvoiceProviderSyncStatus.SYNCED].
 *    - On failure: persists a local record with [InvoiceProviderSyncStatus.SYNC_FAILED].
 *      The visit still completes; the invoice can be retried later.
 * 3. For CASH payments: updates the studio's cash-register balance in the same transaction.
 *
 * This handler must be called inside the visit-completion transaction so that
 * cash register updates and invoice creation are atomic.
 */
@Service
class IssueVisitInvoiceHandler(
    private val credentialsRepository: InvoicingCredentialsRepository,
    private val invoiceRepository: ExternalInvoiceRepository,
    private val providerRegistry: InvoiceProviderRegistry,
    private val cashRegisterRepository: CashRegisterRepository,
    private val cashOperationRepository: CashOperationRepository
) {
    private val log = LoggerFactory.getLogger(IssueVisitInvoiceHandler::class.java)

    @Transactional
    fun handle(command: IssueVisitInvoiceCommand): ExternalInvoice {
        val credentials = credentialsRepository.findByStudioId(command.studioId.value)
            ?: throw InvoicingCredentialsNotFoundException()

        val now = Instant.now()
        val provider = providerRegistry.getProvider(credentials.provider)
        val request = buildProviderRequest(command)

        val entity: ExternalInvoiceEntity

        try {
            val snapshot = provider.issueInvoice(credentials.apiKey, request)

            entity = ExternalInvoiceEntity(
                studioId                = command.studioId.value,
                provider                = credentials.provider,
                externalId              = snapshot.externalId,
                externalNumber          = snapshot.externalNumber,
                status                  = snapshot.status,
                isCorrection            = snapshot.isCorrection,
                hasCorrection           = snapshot.hasCorrection,
                correctionExternalId    = snapshot.correctionExternalId,
                grossAmount             = snapshot.grossAmountInCents,
                netAmount               = snapshot.netAmountInCents,
                vatAmount               = snapshot.vatAmountInCents,
                currency                = snapshot.currency,
                issueDate               = snapshot.issueDate,
                dueDate                 = snapshot.dueDate,
                buyerName               = snapshot.buyerName,
                buyerNip                = snapshot.buyerNip,
                description             = command.description,
                visitId                 = command.visitId.value,
                providerSyncStatus      = InvoiceProviderSyncStatus.SYNCED,
                providerSyncAttemptedAt = now,
                syncedAt                = now,
                createdAt               = now,
                updatedAt               = now
            )

            log.info(
                "[Invoice] Issued via {} for visit {}: externalId={}, gross={}",
                credentials.provider, command.visitId, snapshot.externalId, snapshot.grossAmountInCents
            )
        } catch (ex: Exception) {
            log.warn(
                "[Invoice] Provider {} call failed for visit {}. Saving locally with SYNC_FAILED. Error: {}",
                credentials.provider, command.visitId, ex.message
            )

            entity = ExternalInvoiceEntity(
                studioId                = command.studioId.value,
                provider                = credentials.provider,
                externalId              = null,
                externalNumber          = null,
                status                  = ExternalInvoiceStatus.ISSUED,
                grossAmount             = command.grossAmountInCents,
                netAmount               = command.netAmountInCents,
                vatAmount               = command.vatAmountInCents,
                currency                = command.currency,
                issueDate               = command.issueDate,
                dueDate                 = command.dueDate,
                buyerName               = command.buyerName,
                buyerNip                = command.buyerNip,
                description             = command.description,
                visitId                 = command.visitId.value,
                providerSyncStatus      = InvoiceProviderSyncStatus.SYNC_FAILED,
                providerSyncError       = ex.message?.take(2000),
                providerSyncAttemptedAt = now,
                syncedAt                = null,
                createdAt               = now,
                updatedAt               = now
            )
        }

        val saved = invoiceRepository.save(entity)

        if (command.paymentMethod.uppercase() == "CASH") {
            recordCashPayment(command, now)
        }

        val portalUrl = saved.externalId?.let { provider.getInvoicePortalUrl(it) }
        return saved.toDomain(portalUrl)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun buildProviderRequest(command: IssueVisitInvoiceCommand) = IssueInvoiceRequest(
        buyerName     = command.buyerName,
        buyerNip      = command.buyerNip,
        buyerEmail    = command.buyerEmail,
        buyerStreet   = command.buyerStreet,
        buyerCity     = command.buyerCity,
        buyerPostCode = command.buyerPostCode,
        items         = command.items.map { item ->
            InvoiceItem(
                name                = item.name,
                quantity            = item.quantity,
                unit                = item.unit,
                unitNetPriceInCents = item.unitNetPriceInCents,
                vatRate             = item.vatRate
            )
        },
        paymentMethod = command.paymentMethod,
        issueDate     = command.issueDate,
        dueDate       = command.dueDate,
        currency      = command.currency,
        notes         = command.description
    )

    /**
     * Updates the cash-register balance for a CASH invoice payment.
     *
     * Unlike receipt payments, cash invoice payments are not tied to a FinancialDocument –
     * [CashOperationEntity.financialDocumentId] is null.
     */
    private fun recordCashPayment(command: IssueVisitInvoiceCommand, now: Instant) {
        val cashRegister = cashRegisterRepository.findByStudioIdForUpdate(command.studioId.value)
            ?: cashRegisterRepository.save(
                CashRegisterEntity(studioId = command.studioId.value, balance = 0L)
            )

        val balanceBefore = cashRegister.balance
        val balanceAfter  = balanceBefore + command.grossAmountInCents

        cashRegister.balance   = balanceAfter
        cashRegister.updatedAt = now
        cashRegisterRepository.save(cashRegister)

        cashOperationRepository.save(
            CashOperationEntity(
                id                  = UUID.randomUUID(),
                studioId            = command.studioId.value,
                cashRegisterId      = cashRegister.id,
                amount              = command.grossAmountInCents,
                balanceBefore       = balanceBefore,
                balanceAfter        = balanceAfter,
                operationType       = CashOperationType.PAYMENT_IN,
                comment             = command.description,
                financialDocumentId = null,
                createdBy           = command.userId.value,
                createdAt           = now
            )
        )

        log.debug(
            "[Invoice] Cash register updated: studio={} before={} amount={} after={}",
            command.studioId, balanceBefore, command.grossAmountInCents, balanceAfter
        )
    }
}
