package pl.detailing.crm.ksef.client

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

// ─────────────────────────────────────────────────────────────────────────────
// Auth – challenge
// ─────────────────────────────────────────────────────────────────────────────

data class KsefChallengeResponse(
    val challenge: String,
    val timestamp: Instant,
    val timestampMs: Long
)

// ─────────────────────────────────────────────────────────────────────────────
// Auth – public-key certificates (used for RSA token encryption)
// ─────────────────────────────────────────────────────────────────────────────

data class KsefPublicKeyCertificate(
    val certificate: String,
    val validFrom: OffsetDateTime,
    val validTo: OffsetDateTime,
    val usage: List<String> = emptyList()
)

// ─────────────────────────────────────────────────────────────────────────────
// Auth – KSeF token authentication request
// ─────────────────────────────────────────────────────────────────────────────

data class KsefContextIdentifier(
    val type: String,   // "Nip"
    val value: String   // NIP number
)

data class KsefAuthKsefTokenRequest(
    val challenge: String,
    val contextIdentifier: KsefContextIdentifier,
    val encryptedToken: String
)

// ─────────────────────────────────────────────────────────────────────────────
// Auth – responses
// ─────────────────────────────────────────────────────────────────────────────

data class KsefTokenInfo(
    val token: String,
    val validUntil: OffsetDateTime
)

data class KsefSignatureResponse(
    val referenceNumber: String,
    val authenticationToken: KsefTokenInfo
)

data class KsefStatusInfo(
    val code: Int,
    val description: String?
)

data class KsefAuthStatus(
    val status: KsefStatusInfo
)

data class KsefAuthOperationStatusResponse(
    val accessToken: KsefTokenInfo,
    val refreshToken: KsefTokenInfo
)

// ─────────────────────────────────────────────────────────────────────────────
// Invoice – query filters
// ─────────────────────────────────────────────────────────────────────────────

data class KsefInvoiceQueryDateRange(
    val type: String,       // "InvoicingDate" | "AcquisitionDate" | "IssueDate"
    val from: OffsetDateTime,
    val to: OffsetDateTime
)

data class KsefInvoiceQueryFilters(
    val subjectType: String,            // "Subject1" | "Subject2" | "Subject3"
    val dateRange: KsefInvoiceQueryDateRange,
    val sellerNip: String? = null,
    @JsonProperty("isSelfInvoicing") val isSelfInvoicing: Boolean? = null
)

// ─────────────────────────────────────────────────────────────────────────────
// Invoice – metadata response
// ─────────────────────────────────────────────────────────────────────────────

data class KsefInvoiceMetadataSeller(
    val identifier: String?
)

data class KsefInvoiceMetadataBuyer(
    val identifier: String?
)

data class KsefInvoiceMetadata(
    val ksefNumber: String,
    val invoiceNumber: String?,
    val invoicingDate: OffsetDateTime?,
    val issueDate: LocalDate?,
    val acquisitionDate: OffsetDateTime?,
    val permanentStorageDate: OffsetDateTime?,
    val seller: KsefInvoiceMetadataSeller?,
    val buyer: KsefInvoiceMetadataBuyer?,
    val netAmount: Double?,
    val grossAmount: Double?,
    val vatAmount: Double?,
    val currency: String?,
    val invoiceType: String?,
    @JsonProperty("isSelfInvoicing") val isSelfInvoicing: Boolean?,
    val hasAttachment: Boolean?
)

data class KsefQueryInvoiceMetadataResponse(
    val invoices: List<KsefInvoiceMetadata> = emptyList(),
    val hasMore: Boolean?,
    val isTruncated: Boolean?,
    val permanentStorageHwmDate: OffsetDateTime?
)
