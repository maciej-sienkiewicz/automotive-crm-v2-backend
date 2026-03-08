package pl.detailing.crm.invoicing.domain

import java.time.LocalDate

/**
 * Strategy/Adapter interface for external invoice providers.
 *
 * Each supported invoicing system (inFakt, wFirma, iFirma, Fakturownia)
 * implements this interface. The concrete adapter is resolved at runtime
 * via [pl.detailing.crm.invoicing.InvoiceProviderRegistry] based on the
 * studio's configured [InvoiceProviderType].
 *
 * All methods throw [pl.detailing.crm.invoicing.domain.InvoicingException]
 * subclasses on failure; callers must NOT catch generic exceptions.
 */
interface InvoiceProvider {

    /** Identifies which provider this adapter handles. */
    val type: InvoiceProviderType

    /**
     * Issue a new invoice via the provider's API.
     *
     * @param apiKey Provider API key / token for the studio.
     * @param request Normalized invoice data.
     * @return Snapshot of the freshly created invoice as returned by the provider.
     */
    fun issueInvoice(apiKey: String, request: IssueInvoiceRequest): ExternalInvoiceSnapshot

    /**
     * Fetch current state of a single invoice from the provider's API.
     *
     * @param apiKey Provider API key / token.
     * @param externalId Provider's own invoice identifier.
     * @return Current snapshot including status and correction info.
     */
    fun getInvoice(apiKey: String, externalId: String): ExternalInvoiceSnapshot

    /**
     * Synchronize status and correction flag for an already-tracked invoice.
     * Lighter-weight than [getInvoice] – used by the background sync job.
     *
     * @return Updated snapshot (may be the same as [getInvoice]).
     */
    fun syncInvoiceStatus(apiKey: String, externalId: String): ExternalInvoiceSnapshot

    /**
     * Returns the direct URL where the user can view this invoice on the provider's portal.
     * This is constructed locally (no HTTP call required).
     */
    fun getInvoicePortalUrl(externalId: String): String

    /**
     * Verifies that the provided API key is valid by making a lightweight call to the provider's API.
     *
     * Used during credentials setup to give immediate feedback before saving.
     * Must NOT throw – returns a [CredentialsVerificationResult] with [CredentialsVerificationResult.valid]
     * set to false and an error message on failure.
     *
     * @param apiKey The API key / token to verify.
     */
    fun verifyCredentials(apiKey: String): CredentialsVerificationResult

    /**
     * Fetches all invoices from the provider's API across all pages.
     *
     * Used for the initial import of invoices that were created directly in the provider's
     * system (not through this CRM). Providers that do not support bulk listing may return
     * an empty list.
     *
     * @param apiKey Provider API key / token.
     * @return All invoices available for the account, as normalized snapshots.
     */
    fun listAllInvoices(apiKey: String): List<ExternalInvoiceSnapshot> = emptyList()

    /**
     * Marks an existing invoice as paid on the provider's side.
     *
     * Called when the CRM user manually changes a document status to PAID (e.g. after
     * confirming receipt of a bank transfer). Providers that do not support this operation
     * may leave this as a no-op.
     *
     * @param apiKey   Provider API key / token.
     * @param externalId Provider's own invoice identifier.
     * @param paidDate Optional payment date (YYYY-MM-DD). If null, the provider uses today's date.
     */
    fun markAsPaid(apiKey: String, externalId: String, paidDate: String?) { /* no-op by default */ }
}

data class CredentialsVerificationResult(
    val valid: Boolean,
    /** Human-readable reason shown to the user when [valid] is false. */
    val errorMessage: String? = null
) {
    companion object {
        val OK = CredentialsVerificationResult(valid = true)
        fun failed(reason: String) = CredentialsVerificationResult(valid = false, errorMessage = reason)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Request / snapshot types shared across all providers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Normalized request for issuing a new invoice.
 * The adapter maps these fields to provider-specific request format.
 */
data class IssueInvoiceRequest(
    val buyerName: String,
    val buyerNip: String?,
    val buyerEmail: String?,
    val buyerStreet: String?,
    val buyerCity: String?,
    val buyerPostCode: String?,

    val items: List<InvoiceItem>,

    /** CASH | CARD | TRANSFER */
    val paymentMethod: String,
    val issueDate: LocalDate,

    /** Required when paymentMethod == TRANSFER. */
    val dueDate: LocalDate?,
    val currency: String = "PLN",
    val notes: String?
)

data class InvoiceItem(
    val name: String,
    val quantity: Double,

    /** Unit label, e.g. "szt.", "godz.", "usł." */
    val unit: String,

    /** Net unit price in grosz (1/100 PLN). */
    val unitNetPriceInCents: Long,

    /** VAT rate in percent (23, 8, 5, 0) or -1 for VAT_ZW. */
    val vatRate: Int
)

/**
 * Provider-agnostic snapshot of an invoice as fetched from the external API.
 * Used both as the return type of [InvoiceProvider.issueInvoice] and
 * [InvoiceProvider.getInvoice].
 */
data class ExternalInvoiceSnapshot(
    val externalId: String,
    val externalNumber: String?,
    val status: ExternalInvoiceStatus,
    val isCorrection: Boolean,
    val hasCorrection: Boolean,
    val correctionExternalId: String?,
    val grossAmountInCents: Long,
    val netAmountInCents: Long,
    val vatAmountInCents: Long,
    val currency: String,
    val issueDate: LocalDate,
    val dueDate: LocalDate?,
    val buyerName: String?,
    val buyerNip: String?,
    /**
     * Free-text notes / description attached to the invoice by the issuer.
     * Used by [pl.detailing.crm.finance.document.ImportProviderInvoicesHandler] to detect
     * CRM-originated invoices via the embedded [crm:visitId:UUID] tag.
     */
    val notes: String? = null
)
