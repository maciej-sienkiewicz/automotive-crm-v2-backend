package pl.detailing.crm.invoicing.adapter.infakt.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

// ─────────────────────────────────────────────────────────────────────────────
// inFakt API – Invoice Response DTO
// Docs: https://docs.infakt.pl/
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Represents a single invoice as returned by inFakt API (GET /v3/invoices/{id}.json
 * and as element in GET /v3/invoices.json).
 *
 * Monetary values from inFakt are in grosze (Integer type from their side,
 * 1/100 PLN). We map them directly to Long.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class InfaktInvoiceDto(
    val id: String,
    val number: String?,
    val status: String?,

    @JsonProperty("sale_date")
    val saleDate: String?,

    @JsonProperty("invoice_date")
    val invoiceDate: String?,

    @JsonProperty("payment_date")
    val paymentDate: String?,

    @JsonProperty("payment_method")
    val paymentMethod: String?,

    /** Gross price in grosz. */
    @JsonProperty("gross_price")
    val grossPrice: Long?,

    /** Net price in grosz. */
    @JsonProperty("net_price")
    val netPrice: Long?,

    /** VAT price in grosz. */
    @JsonProperty("tax_price")
    val vatPrice: Long?,

    val currency: String?,

    @JsonProperty("client_id")
    val clientId: Long?,

    @JsonProperty("client_company_name")
    val clientCompanyName: String?,

    @JsonProperty("client_first_name")
    val clientFirstName: String?,

    @JsonProperty("client_last_name")
    val clientLastName: String?,

    @JsonProperty("client_nip")
    val clientNip: String?,

    val notes: String?,

    /** True if this invoice is a correction (credit note). */
    val correction: Boolean? = false,

    /** ID of the invoice that was corrected by this invoice. */
    @JsonProperty("corrected_invoice_id")
    val correctedInvoiceId: String?,

    /** True if a correction has been issued for this invoice. */
    @JsonProperty("has_correction")
    val hasCorrection: Boolean? = false,

    /** ID of the correction invoice issued for this invoice. */
    @JsonProperty("correction_id")
    val correctionId: String?,

    val services: List<InfaktServiceDto>? = null
) {
    /** Returns a display name for the buyer, combining available name fields. */
    fun buyerDisplayName(): String? =
        clientCompanyName?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(clientFirstName, clientLastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class InfaktServiceDto(
    val name: String?,

    @JsonProperty("quantity")
    val count: Double?,
    val unit: String?,

    @JsonProperty("unit_net_price")
    val unitNetPrice: Long?,

    @JsonProperty("tax_symbol")
    val taxSymbol: String?
)

// ─────────────────────────────────────────────────────────────────────────────
// inFakt API – List Response Wrapper
// ─────────────────────────────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class InfaktInvoiceListResponse(
    val entities: List<InfaktInvoiceDto>? = null,

    @JsonProperty("metainfo")
    val metaData: InfaktMetaData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class InfaktMetaData(
    @JsonProperty("total_count")
    val total: Int?,
    val count: Int?,
    val next: String? = null,
    val previous: String? = null
)

// ─────────────────────────────────────────────────────────────────────────────
// inFakt API – Create Invoice Request DTO
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Root wrapper for invoice creation request body.
 * inFakt expects: { "invoice": { ... } }
 */
data class InfaktCreateInvoiceRequest(
    val invoice: InfaktInvoicePayload
)

data class InfaktInvoicePayload(
    /** Invoice type. "vat" covers standard VAT invoices. */
    val kind: String = "vat",

    @JsonProperty("payment_method")
    val paymentMethod: String,

    /** Date of sale / service provision. Format: YYYY-MM-DD. */
    @JsonProperty("sale_date")
    val saleDate: String,

    /** Date when the invoice was issued. Format: YYYY-MM-DD. */
    @JsonProperty("invoice_date")
    val invoiceDate: String,

    /** Payment due date. Format: YYYY-MM-DD. Null for immediate payments. */
    @JsonProperty("payment_date")
    val paymentDate: String?,

    val currency: String = "PLN",

    @JsonProperty("client_company_name")
    val clientCompanyName: String?,

    @JsonProperty("client_nip")
    val clientNip: String?,

    @JsonProperty("client_email")
    val clientEmail: String?,

    @JsonProperty("client_street")
    val clientStreet: String?,

    @JsonProperty("client_city")
    val clientCity: String?,

    @JsonProperty("client_post_code")
    val clientPostCode: String?,

    val services: List<InfaktServicePayload>,

    val notes: String?
)

data class InfaktServicePayload(
    val name: String,
    val count: Double,
    val unit: String,

    /** Net unit price in grosz. */
    @JsonProperty("unit_net_price")
    val unitNetPrice: Long,

    /** VAT rate symbol, e.g. "vat23", "vat8", "vat5", "vat0", "zw". */
    @JsonProperty("tax_symbol")
    val taxSymbol: String
)

// ─────────────────────────────────────────────────────────────────────────────
// inFakt API – Error Response DTO
// ─────────────────────────────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class InfaktErrorResponse(
    val errors: Map<String, List<String>>? = null,
    val message: String? = null,
    val error: String? = null
) {
    /** Extracts all error messages into a flat list for display. */
    fun toErrorMessages(): List<String> {
        val fieldErrors = errors?.flatMap { (field, messages) ->
            messages.map { msg -> "$field: $msg" }
        } ?: emptyList()

        val topLevel = listOfNotNull(message, error).filter { it.isNotBlank() }

        return (fieldErrors + topLevel).ifEmpty { listOf("Nieznany błąd API") }
    }
}
