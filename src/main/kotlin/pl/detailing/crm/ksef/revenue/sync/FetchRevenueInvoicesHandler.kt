package pl.detailing.crm.ksef.revenue.sync

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceMetadata
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQueryDateRange
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQueryDateType
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQueryFilters
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQuerySubjectType
import pl.akmf.ksef.sdk.client.model.util.SortOrder
import pl.detailing.crm.ksef.auth.KsefAuthService
import pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus
import pl.detailing.crm.ksef.revenue.domain.RevenueInvoiceType
import pl.detailing.crm.ksef.revenue.domain.RevenueSource
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceEntity
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import pl.detailing.crm.shared.StudioId
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.time.ZoneId

data class FetchRevenueCommand(
    val studioId: StudioId,
    val dateFrom: OffsetDateTime,
    val dateTo: OffsetDateTime,
    val pageSize: Int = 100
)

data class FetchRevenueResult(val fetched: Int, val matched: Int, val duplicatesSuspected: Int)

/**
 * Pull faktur sprzedażowych studia z KSeF (subjectType=SUBJECT1 — podmiot jako
 * sprzedawca). Dzięki temu statystyki przychodów są kompletne także wtedy, gdy
 * klient wystawi część faktur poza CRM (program księgowy, Aplikacja Podatnika).
 *
 * Idempotentny upsert po numerze KSeF:
 * - numer znany (faktura wystawiona w CRM wraca pullem) → żadnego nowego rekordu;
 *   jedynie domknięcie stanu, gdyby pull wyprzedził polling sesji,
 * - numer nieznany → nowy rekord source=EXTERNAL (status ACCEPTED — istnieje w KSeF),
 *   po którym uruchamiany jest detektor podwójnego fakturowania.
 *
 * Metadane zapytania wystarczają (kwoty, strony, typ) — nie pobieramy XML,
 * co oszczędza restrykcyjne limity żądań GET /invoices/ksef/{nr}.
 */
@Service
class FetchRevenueInvoicesHandler(
    private val ksefClient: KSeFClient,
    private val authService: KsefAuthService,
    private val repository: KsefRevenueInvoiceRepository,
    private val duplicateDetector: RevenueDuplicateDetector
) {
    private val log = LoggerFactory.getLogger(FetchRevenueInvoicesHandler::class.java)

    @Transactional
    fun handle(command: FetchRevenueCommand): FetchRevenueResult {
        val pageSize = command.pageSize.coerceIn(10, 250)
        log.info(
            "Fetching KSeF revenue studio={} from={} to={}",
            command.studioId, command.dateFrom, command.dateTo
        )

        val accessToken = authService.getValidAccessToken(command.studioId)

        val filters = InvoiceQueryFilters().apply {
            subjectType = InvoiceQuerySubjectType.SUBJECT1
            dateRange = InvoiceQueryDateRange(InvoiceQueryDateType.INVOICING, command.dateFrom, command.dateTo)
        }

        var fetched = 0
        var matched = 0
        var duplicates = 0

        for (metadata in fetchAllPages(filters, accessToken, pageSize)) {
            val existing = repository.findByStudioIdAndKsefNumber(command.studioId.value, metadata.ksefNumber)
            if (existing != null) {
                // Nasza faktura wróciła pullem — domykamy stan, gdyby polling sesji nie zdążył
                if (existing.ksefStatus != KsefRevenueStatus.ACCEPTED) {
                    existing.markAccepted(metadata.ksefNumber)
                    repository.save(existing)
                }
                matched++
                continue
            }

            // Pull wyprzedził polling sesji: dokument jest nasz, ale numeru KSeF
            // jeszcze nie zapisaliśmy. Dopasowanie po numerze własnym zamyka stan
            // zamiast tworzyć fantomowy rekord EXTERNAL o tym samym numerze.
            val awaiting = metadata.invoiceNumber
                ?.let { repository.findAwaitingConfirmationByNumber(command.studioId.value, it) }
                ?.firstOrNull()
            if (awaiting != null) {
                awaiting.markAccepted(metadata.ksefNumber)
                repository.save(awaiting)
                matched++
                continue
            }

            val external = toExternalEntity(command.studioId, metadata)
            repository.save(external)
            fetched++

            if (duplicateDetector.checkExternalInvoice(external)) duplicates++
        }

        log.info(
            "KSeF revenue fetch complete studio={}: external={} matched={} duplicatesSuspected={}",
            command.studioId, fetched, matched, duplicates
        )
        return FetchRevenueResult(fetched, matched, duplicates)
    }

    // ── Private ────────────────────────────────────────────────────────────────

    private fun toExternalEntity(studioId: StudioId, metadata: InvoiceMetadata): KsefRevenueInvoiceEntity {
        // Typy korygujące w metadanych KSeF 2.0: KOR, KOR_ZAL, KOR_ROZ, KOR_PEF, KOR_VAT_SP
        val isCorrection = metadata.invoiceType?.name?.startsWith("KOR") == true
        val issueDate = metadata.issueDate
            ?: metadata.invoicingDate?.atZoneSameInstant(ZoneId.of("Europe/Warsaw"))?.toLocalDate()
            ?: OffsetDateTime.now().toLocalDate()

        return KsefRevenueInvoiceEntity(
            studioId = studioId.value,
            source = RevenueSource.EXTERNAL,
            ksefStatus = KsefRevenueStatus.ACCEPTED,
            invoiceNumber = metadata.invoiceNumber ?: metadata.ksefNumber,
            ksefNumber = metadata.ksefNumber,
            invoiceType = if (isCorrection) RevenueInvoiceType.KOR else RevenueInvoiceType.VAT,
            issueDate = issueDate,
            sellerNip = metadata.seller?.nip,
            sellerName = metadata.seller?.name,
            buyerNip = metadata.buyer?.identifier?.value?.replace(Regex("[^0-9]"), "")?.ifBlank { null },
            buyerName = metadata.buyer?.name,
            totalNet = toGrosz(metadata.netAmount),
            totalVat = toGrosz(metadata.vatAmount),
            totalGross = toGrosz(metadata.grossAmount),
            currency = metadata.currency ?: "PLN",
            invoiceHash = metadata.invoiceHash
        )
    }

    private fun toGrosz(amount: Double?): Long =
        amount?.let { BigDecimal.valueOf(it).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong() } ?: 0L

    private fun fetchAllPages(filters: InvoiceQueryFilters, accessToken: String, pageSize: Int) = buildList {
        var offset = 0
        var hasMore = true
        while (hasMore) {
            val response = ksefClient.queryInvoiceMetadata(offset, pageSize, SortOrder.ASC, filters, accessToken)
            addAll(response.invoices)
            hasMore = response.hasMore == true && response.invoices.isNotEmpty()
            offset += pageSize
        }
    }
}
