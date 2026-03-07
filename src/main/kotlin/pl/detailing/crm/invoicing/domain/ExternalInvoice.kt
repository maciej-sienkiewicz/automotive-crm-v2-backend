package pl.detailing.crm.invoicing.domain

import pl.detailing.crm.shared.ExternalInvoiceId
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Normalized representation of an invoice.
 *
 * Invoices are created locally when a visit is completed, then synchronised with the
 * configured external provider. An invoice without an [externalId] has not yet been
 * sent to the provider (see [providerSyncStatus]).
 *
 * All monetary values are stored in grosz (1/100 PLN).
 */
data class ExternalInvoice(
    val id: ExternalInvoiceId,
    val studioId: StudioId,

    /** Provider this invoice belongs to. Null when the sync has not yet succeeded. */
    val provider: InvoiceProviderType?,

    /** Provider's own identifier. Null until successfully sent to the provider. */
    val externalId: String?,

    /** Human-readable invoice number assigned by the provider (e.g. "FV/2024/01/001"). */
    val externalNumber: String?,

    val status: ExternalInvoiceStatus,

    /** True if this invoice is itself a correction (credit note) for another invoice. */
    val isCorrection: Boolean,

    /** True if another invoice has been issued correcting this one. */
    val hasCorrection: Boolean,

    /** Provider ID of the correction invoice, if one has been issued for this invoice. */
    val correctionExternalId: String?,

    /** Gross amount in grosz (1/100 PLN). */
    val grossAmount: Long,

    /** Net amount in grosz. */
    val netAmount: Long,

    /** VAT amount in grosz. Invariant: netAmount + vatAmount == grossAmount. */
    val vatAmount: Long,

    val currency: String,
    val issueDate: LocalDate,

    /** Payment due date; null for immediate payments. */
    val dueDate: LocalDate?,

    val buyerName: String?,
    val buyerNip: String?,
    val description: String?,

    /** Optional link to the visit this invoice was issued for. */
    val visitId: UUID?,

    /** Synchronization state with the external provider. */
    val providerSyncStatus: InvoiceProviderSyncStatus,

    /** Human-readable error from the last failed provider sync attempt. */
    val providerSyncError: String?,

    /** Timestamp of the last provider sync attempt. */
    val providerSyncAttemptedAt: Instant?,

    /** Direct URL to this invoice on the provider's web portal. Null if not yet synced. */
    val externalUrl: String?,

    /** Last time data was pulled from provider's API. Null for not-yet-synced invoices. */
    val syncedAt: Instant?,

    val createdAt: Instant,
    val updatedAt: Instant
)
