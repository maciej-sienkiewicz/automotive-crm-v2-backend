package pl.detailing.crm.invoicing.domain

import pl.detailing.crm.shared.ExternalInvoiceId
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.LocalDate

/**
 * Normalized representation of an invoice issued via an external provider.
 *
 * All monetary values are stored in grosz (1/100 PLN).
 * [externalId] is the provider's own identifier for this invoice.
 * [externalUrl] is a direct link to the invoice on the provider's portal.
 * [hasCorrection] indicates whether a credit note / correction has been issued for this invoice.
 */
data class ExternalInvoice(
    val id: ExternalInvoiceId,
    val studioId: StudioId,
    val provider: InvoiceProviderType,

    /** Provider's own identifier (UUID or numeric ID depending on provider). */
    val externalId: String,

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

    /** Direct URL to this invoice on the provider's web portal. */
    val externalUrl: String,

    /** Last time data was pulled from provider's API. */
    val syncedAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant
)
