package pl.detailing.crm.invoicing

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.invoicing.credentials.InvoicingCredentialsEntity
import pl.detailing.crm.invoicing.credentials.InvoicingCredentialsRepository
import pl.detailing.crm.invoicing.domain.*
import pl.detailing.crm.invoicing.issue.InvoiceItemCommand
import pl.detailing.crm.invoicing.issue.IssueInvoiceCommand
import pl.detailing.crm.invoicing.issue.IssueInvoiceHandler
import pl.detailing.crm.invoicing.sync.SyncInvoiceStatusCommand
import pl.detailing.crm.invoicing.sync.SyncInvoiceStatusHandler
import pl.detailing.crm.invoicing.view.GetExternalInvoiceHandler
import pl.detailing.crm.invoicing.view.GetExternalInvoiceQuery
import pl.detailing.crm.invoicing.view.ListExternalInvoicesHandler
import pl.detailing.crm.invoicing.view.ListExternalInvoicesQuery
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UserRole
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/invoicing")
class InvoicingController(
    private val credentialsRepository: InvoicingCredentialsRepository,
    private val issueInvoiceHandler: IssueInvoiceHandler,
    private val getInvoiceHandler: GetExternalInvoiceHandler,
    private val listInvoicesHandler: ListExternalInvoicesHandler,
    private val syncStatusHandler: SyncInvoiceStatusHandler,
    private val providerRegistry: InvoiceProviderRegistry
) {

    // ─────────────────────────────────────────────────────────────────────────
    // Credentials management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Configure the invoicing provider and API key for the current studio.
     * Replaces any previously configured provider.
     * POST /api/v1/invoicing/credentials
     */
    @PostMapping("/credentials")
    @Transactional
    fun saveCredentials(
        @RequestBody request: SaveInvoicingCredentialsRequest
    ): ResponseEntity<InvoicingCredentialsResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Tylko właściciel może konfigurować integrację z dostawcą faktur")
        }

        val provider = parseProvider(request.provider)

        credentialsRepository.deleteByStudioId(principal.studioId.value)

        val entity = credentialsRepository.save(
            InvoicingCredentialsEntity(
                studioId  = principal.studioId.value,
                provider  = provider,
                apiKey    = request.apiKey.trim()
            )
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(entity.toResponse())
    }

    /**
     * Retrieve current invoicing provider configuration (API key masked).
     * GET /api/v1/invoicing/credentials
     */
    @GetMapping("/credentials")
    fun getCredentials(): ResponseEntity<InvoicingCredentialsResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Tylko właściciel może wyświetlić konfigurację integracji")
        }

        val entity = credentialsRepository.findByStudioId(principal.studioId.value)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(entity.toResponse())
    }

    /**
     * Remove invoicing provider configuration.
     * DELETE /api/v1/invoicing/credentials
     */
    @DeleteMapping("/credentials")
    @Transactional
    fun deleteCredentials(): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Tylko właściciel może usunąć konfigurację integracji")
        }

        credentialsRepository.deleteByStudioId(principal.studioId.value)
        return ResponseEntity.noContent().build()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Invoice operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Issue a new invoice via the configured provider.
     * POST /api/v1/invoicing/invoices
     */
    @PostMapping("/invoices")
    fun issueInvoice(
        @RequestBody request: IssueInvoiceRequest
    ): ResponseEntity<ExternalInvoiceResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireManagerOrOwner(principal.role)

        val command = IssueInvoiceCommand(
            studioId      = principal.studioId,
            buyerName     = request.buyerName,
            buyerNip      = request.buyerNip,
            buyerEmail    = request.buyerEmail,
            buyerStreet   = request.buyerStreet,
            buyerCity     = request.buyerCity,
            buyerPostCode = request.buyerPostCode,
            items         = request.items.map { item ->
                InvoiceItemCommand(
                    name                = item.name,
                    quantity            = item.quantity,
                    unit                = item.unit,
                    unitNetPriceInCents = item.unitNetPriceInCents,
                    vatRate             = item.vatRate
                )
            },
            paymentMethod = request.paymentMethod,
            issueDate     = request.issueDate,
            dueDate       = request.dueDate,
            currency      = request.currency ?: "PLN",
            notes         = request.notes
        )

        val invoice = issueInvoiceHandler.handle(command)
        return ResponseEntity.status(HttpStatus.CREATED).body(invoice.toResponse())
    }

    /**
     * List invoices from the local cache (no provider API call).
     * GET /api/v1/invoicing/invoices?page=1&size=20
     */
    @GetMapping("/invoices")
    fun listInvoices(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<ExternalInvoiceListResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = listInvoicesHandler.handle(
            ListExternalInvoicesQuery(
                studioId = principal.studioId,
                page     = maxOf(1, page),
                pageSize = size.coerceIn(1, 100)
            )
        )

        return ResponseEntity.ok(
            ExternalInvoiceListResponse(
                invoices = result.invoices.map { it.toResponse() },
                total    = result.total,
                page     = result.page,
                pageSize = result.pageSize
            )
        )
    }

    /**
     * Get a single invoice with refreshed status from the provider.
     * GET /api/v1/invoicing/invoices/{id}
     */
    @GetMapping("/invoices/{id}")
    fun getInvoice(@PathVariable id: UUID): ResponseEntity<ExternalInvoiceResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val invoice = getInvoiceHandler.handle(
            GetExternalInvoiceQuery(studioId = principal.studioId, invoiceId = id)
        )

        return ResponseEntity.ok(invoice.toResponse())
    }

    /**
     * Synchronize status of a single invoice (or all active invoices) from the provider.
     * POST /api/v1/invoicing/invoices/sync           → sync all active
     * POST /api/v1/invoicing/invoices/{id}/sync      → sync single invoice
     */
    @PostMapping("/invoices/sync")
    fun syncAllInvoices(): ResponseEntity<SyncResultResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireManagerOrOwner(principal.role)

        val result = syncStatusHandler.handle(
            SyncInvoiceStatusCommand(studioId = principal.studioId, invoiceId = null)
        )

        return ResponseEntity.ok(result.toResponse())
    }

    @PostMapping("/invoices/{id}/sync")
    fun syncSingleInvoice(@PathVariable id: UUID): ResponseEntity<ExternalInvoiceResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireManagerOrOwner(principal.role)

        syncStatusHandler.handle(
            SyncInvoiceStatusCommand(studioId = principal.studioId, invoiceId = id)
        )

        val invoice = getInvoiceHandler.handle(
            GetExternalInvoiceQuery(studioId = principal.studioId, invoiceId = id)
        )

        return ResponseEntity.ok(invoice.toResponse())
    }

    /**
     * Get a direct URL to view the invoice on the provider's portal.
     * GET /api/v1/invoicing/invoices/{id}/portal-url
     *
     * This redirects the user to the provider's web interface where they can
     * view, download, or manage the invoice.
     */
    @GetMapping("/invoices/{id}/portal-url")
    fun getPortalUrl(@PathVariable id: UUID): ResponseEntity<InvoicePortalUrlResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val invoice = getInvoiceHandler.handle(
            GetExternalInvoiceQuery(studioId = principal.studioId, invoiceId = id)
        )

        return ResponseEntity.ok(InvoicePortalUrlResponse(url = invoice.externalUrl))
    }

    /**
     * Returns a list of all supported invoicing providers.
     * GET /api/v1/invoicing/providers
     */
    @GetMapping("/providers")
    fun listProviders(): ResponseEntity<List<InvoiceProviderInfoResponse>> {
        val providers = InvoiceProviderType.entries.map { provider ->
            InvoiceProviderInfoResponse(
                type        = provider.name,
                displayName = provider.displayName,
                supported   = runCatching { providerRegistry.getProvider(provider) }.isSuccess
            )
        }
        return ResponseEntity.ok(providers)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun requireManagerOrOwner(role: UserRole) {
        if (role != UserRole.OWNER && role != UserRole.MANAGER) {
            throw ForbiddenException("Tylko właściciel lub manager może zarządzać fakturami")
        }
    }

    private fun parseProvider(value: String): InvoiceProviderType {
        return runCatching { InvoiceProviderType.valueOf(value.uppercase()) }.getOrElse {
            throw ValidationException(
                "Nieznany dostawca faktur: '$value'. Dostępne: ${InvoiceProviderType.entries.joinToString { it.name }}"
            )
        }
    }

    private fun InvoicingCredentialsEntity.toResponse() = InvoicingCredentialsResponse(
        provider        = provider.name,
        providerLabel   = provider.displayName,
        apiKeyMasked    = maskApiKey(apiKey),
        createdAt       = createdAt,
        updatedAt       = updatedAt
    )

    private fun maskApiKey(key: String): String =
        if (key.length <= 8) "****" else key.take(4) + "****" + key.takeLast(4)

    private fun pl.detailing.crm.invoicing.sync.SyncResult.toResponse() = SyncResultResponse(
        synced = synced,
        failed = failed,
        errors = errors
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Request DTOs
// ─────────────────────────────────────────────────────────────────────────────

data class SaveInvoicingCredentialsRequest(
    /** Provider type: INFAKT | WFIRMA | IFIRMA | FAKTUROWNIA */
    val provider: String,
    val apiKey: String
)

data class IssueInvoiceRequest(
    val buyerName: String,
    val buyerNip: String?,
    val buyerEmail: String?,
    val buyerStreet: String?,
    val buyerCity: String?,
    val buyerPostCode: String?,
    val items: List<InvoiceItemRequest>,

    /** CASH | CARD | TRANSFER */
    val paymentMethod: String,

    val issueDate: LocalDate,

    /** Required when paymentMethod == TRANSFER. */
    val dueDate: LocalDate?,

    /** ISO-4217 currency code, defaults to PLN. */
    val currency: String? = "PLN",

    val notes: String?
)

data class InvoiceItemRequest(
    val name: String,
    val quantity: Double,

    /** Unit label, e.g. "szt.", "godz.", "usł." */
    val unit: String,

    /** Net unit price in grosz (1/100 PLN). */
    val unitNetPriceInCents: Long,

    /** VAT rate in percent: 23, 8, 5, 0 or -1 for VAT_ZW (exempt). */
    val vatRate: Int
)

// ─────────────────────────────────────────────────────────────────────────────
// Response DTOs
// ─────────────────────────────────────────────────────────────────────────────

data class InvoicingCredentialsResponse(
    val provider: String,
    val providerLabel: String,
    val apiKeyMasked: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class ExternalInvoiceResponse(
    val id: String,
    val provider: String,
    val providerLabel: String,
    val externalId: String,
    val externalNumber: String?,
    val status: String,
    val statusLabel: String,

    /** True if this invoice is a correction (credit note) for another invoice. */
    val isCorrection: Boolean,

    /** True if a correction has been issued for this invoice. */
    val hasCorrection: Boolean,

    /** Provider ID of the correction invoice issued for this invoice. */
    val correctionExternalId: String?,

    /** Gross amount in grosz (1/100 PLN). */
    val grossAmount: Long,

    /** Net amount in grosz. */
    val netAmount: Long,

    /** VAT amount in grosz. */
    val vatAmount: Long,

    val currency: String,
    val issueDate: String,
    val dueDate: String?,
    val buyerName: String?,
    val buyerNip: String?,
    val description: String?,

    /** Direct URL to view this invoice on the provider's portal. */
    val externalUrl: String,

    val syncedAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class ExternalInvoiceListResponse(
    val invoices: List<ExternalInvoiceResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)

data class InvoicePortalUrlResponse(
    val url: String
)

data class SyncResultResponse(
    val synced: Int,
    val failed: Int,
    val errors: List<String>
)

data class InvoiceProviderInfoResponse(
    val type: String,
    val displayName: String,
    /** True if an adapter has been implemented for this provider. */
    val supported: Boolean
)

// ─────────────────────────────────────────────────────────────────────────────
// Domain → Response mapping
// ─────────────────────────────────────────────────────────────────────────────

private fun ExternalInvoice.toResponse() = ExternalInvoiceResponse(
    id                   = id.toString(),
    provider             = provider.name,
    providerLabel        = provider.displayName,
    externalId           = externalId,
    externalNumber       = externalNumber,
    status               = status.name,
    statusLabel          = status.displayName,
    isCorrection         = isCorrection,
    hasCorrection        = hasCorrection,
    correctionExternalId = correctionExternalId,
    grossAmount          = grossAmount,
    netAmount            = netAmount,
    vatAmount            = vatAmount,
    currency             = currency,
    issueDate            = issueDate.toString(),
    dueDate              = dueDate?.toString(),
    buyerName            = buyerName,
    buyerNip             = buyerNip,
    description          = description,
    externalUrl          = externalUrl,
    syncedAt             = syncedAt,
    createdAt            = createdAt,
    updatedAt            = updatedAt
)
