package pl.detailing.crm.invoicing.adapter.infakt

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.detailing.crm.invoicing.adapter.infakt.dto.InfaktCreateInvoiceRequest
import pl.detailing.crm.invoicing.adapter.infakt.dto.InfaktInvoiceDto
import pl.detailing.crm.invoicing.adapter.infakt.dto.InfaktInvoicePayload
import pl.detailing.crm.invoicing.adapter.infakt.dto.InfaktServicePayload
import pl.detailing.crm.invoicing.domain.*

/**
 * Adapter for the inFakt invoicing platform (https://www.infakt.pl/).
 *
 * Maps between our normalized domain model and inFakt's REST API v3.
 *
 * Status mapping (inFakt → [ExternalInvoiceStatus]):
 *   draft     → DRAFT
 *   sent      → SENT
 *   paid      → PAID
 *   overdue   → OVERDUE
 *   cancelled → CANCELLED
 *   (others)  → ISSUED
 *
 * VAT rate mapping ([InvoiceItem.vatRate] → inFakt tax_symbol):
 *   23  → "23"
 *   8   → "8"
 *   5   → "5"
 *   0   → "0"
 *   -1  → "zw"   (VAT exempt)
 *
 * Payment method mapping ([IssueInvoiceRequest.paymentMethod] → inFakt payment_method):
 *   CASH     → "cash"
 *   CARD     → "card"
 *   TRANSFER → "transfer"
 */
@Component
class InfaktAdapter(
    private val apiClient: InfaktApiClient
) : InvoiceProvider {

    override val type: InvoiceProviderType = InvoiceProviderType.INFAKT

    companion object {
        const val PORTAL_BASE_URL = "https://app.infakt.pl/app/faktury"
        private val log = LoggerFactory.getLogger(InfaktAdapter::class.java)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // InvoiceProvider implementation
    // ─────────────────────────────────────────────────────────────────────────

    override fun issueInvoice(apiKey: String, request: IssueInvoiceRequest): ExternalInvoiceSnapshot {
        val payload = mapToInfaktRequest(request)
        val response = apiClient.createInvoice(apiKey, payload)
        return mapToSnapshot(response)
    }

    override fun getInvoice(apiKey: String, externalId: String): ExternalInvoiceSnapshot {
        val response = apiClient.getInvoice(apiKey, externalId)
        return mapToSnapshot(response)
    }

    override fun syncInvoiceStatus(apiKey: String, externalId: String): ExternalInvoiceSnapshot {
        return getInvoice(apiKey, externalId)
    }

    override fun getInvoicePortalUrl(externalId: String): String =
        "$PORTAL_BASE_URL/$externalId"

    override fun verifyCredentials(apiKey: String): CredentialsVerificationResult {
        return when (apiClient.ping(apiKey)) {
            true  -> CredentialsVerificationResult.OK
            false -> CredentialsVerificationResult.failed(
                "Nieprawidłowy klucz API inFakt. Sprawdź klucz w panelu inFakt: Ustawienia → API."
            )
            null  -> CredentialsVerificationResult.failed(
                "Nie udało się połączyć z serwerem inFakt. Sprawdź połączenie internetowe i spróbuj ponownie."
            )
        }
    }

    override fun listAllInvoices(apiKey: String): List<ExternalInvoiceSnapshot> {
        log.info("[InFakt] listAllInvoices: starting full import")
        val result = mutableListOf<ExternalInvoiceSnapshot>()
        var page = 1
        while (true) {
            log.info("[InFakt] listAllInvoices: fetching page {}", page)
            val response = apiClient.listInvoices(apiKey, page, InfaktApiClient.PAGE_SIZE)
            val entities = response.entities
            log.info(
                "[InFakt] listAllInvoices: page {} → entities={}, total={}",
                page, entities?.size, response.metaData?.total
            )
            if (entities.isNullOrEmpty()) break
            result.addAll(entities.map { mapToSnapshot(it) })
            val total = response.metaData?.total ?: break
            if (result.size >= total) break
            page++
        }
        log.info("[InFakt] listAllInvoices: done, total fetched={}", result.size)
        return result
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mapping: domain request → inFakt request
    // ─────────────────────────────────────────────────────────────────────────

    private fun mapToInfaktRequest(request: IssueInvoiceRequest): InfaktCreateInvoiceRequest =
        InfaktCreateInvoiceRequest(
            invoice = InfaktInvoicePayload(
                kind              = "vat",
                paymentMethod     = mapPaymentMethod(request.paymentMethod),
                saleDate          = request.issueDate.toString(),
                invoiceDate       = request.issueDate.toString(),
                paymentDate       = request.dueDate?.toString(),
                currency          = request.currency,
                clientCompanyName = request.buyerName.takeIf { it.isNotBlank() },
                clientNip         = request.buyerNip?.trim()?.takeIf { it.isNotBlank() },
                clientEmail       = request.buyerEmail?.trim()?.takeIf { it.isNotBlank() },
                clientStreet      = request.buyerStreet?.trim()?.takeIf { it.isNotBlank() },
                clientCity        = request.buyerCity?.trim()?.takeIf { it.isNotBlank() },
                clientPostCode    = request.buyerPostCode?.trim()?.takeIf { it.isNotBlank() },
                services          = request.items.map { mapItem(it) },
                notes             = request.notes?.trim()?.takeIf { it.isNotBlank() }
            )
        )

    private fun mapItem(item: InvoiceItem): InfaktServicePayload =
        InfaktServicePayload(
            name          = item.name,
            count         = item.quantity,
            unit          = item.unit,
            unitNetPrice  = item.unitNetPriceInCents,
            taxSymbol     = mapVatRate(item.vatRate)
        )

    private fun mapPaymentMethod(method: String): String = when (method.uppercase()) {
        "CASH"     -> "cash"
        "CARD"     -> "card"
        "TRANSFER" -> "transfer"
        else       -> "transfer"
    }

    private fun mapVatRate(vatRate: Int): String = when (vatRate) {
        23   -> "23"
        8    -> "8"
        5    -> "5"
        0    -> "0"
        -1   -> "zw"
        else -> "23"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mapping: inFakt response → domain snapshot
    // ─────────────────────────────────────────────────────────────────────────

    private fun mapToSnapshot(dto: InfaktInvoiceDto): ExternalInvoiceSnapshot {
        val issueDate = dto.saleDate?.let {
            runCatching { java.time.LocalDate.parse(it) }.getOrNull()
        } ?: dto.invoiceDate?.let {
            runCatching { java.time.LocalDate.parse(it) }.getOrNull()
        } ?: java.time.LocalDate.now()

        val dueDate = dto.paymentDate?.let {
            runCatching { java.time.LocalDate.parse(it) }.getOrNull()
        }

        return ExternalInvoiceSnapshot(
            externalId           = dto.id,
            externalNumber       = dto.number?.takeIf { it.isNotBlank() },
            status               = mapStatus(dto.status),
            isCorrection         = dto.correction == true,
            hasCorrection        = dto.hasCorrection == true,
            correctionExternalId = dto.correctionId?.takeIf { it.isNotBlank() },
            grossAmountInCents   = dto.grossPrice ?: 0L,
            netAmountInCents     = dto.netPrice ?: 0L,
            vatAmountInCents     = dto.vatPrice ?: 0L,
            currency             = dto.currency ?: "PLN",
            issueDate            = issueDate,
            dueDate              = dueDate,
            buyerName            = dto.buyerDisplayName(),
            buyerNip             = dto.clientNip?.takeIf { it.isNotBlank() }
        )
    }

    private fun mapStatus(infaktStatus: String?): ExternalInvoiceStatus = when (infaktStatus?.lowercase()) {
        "draft"     -> ExternalInvoiceStatus.DRAFT
        "sent"      -> ExternalInvoiceStatus.SENT
        "paid"      -> ExternalInvoiceStatus.PAID
        "overdue"   -> ExternalInvoiceStatus.OVERDUE
        "cancelled" -> ExternalInvoiceStatus.CANCELLED
        else        -> ExternalInvoiceStatus.ISSUED
    }
}
