package pl.detailing.crm.ksef.fetch

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQueryDateRange
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQueryDateType
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQueryFilters
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQuerySubjectType
import pl.akmf.ksef.sdk.client.model.util.SortOrder
import pl.detailing.crm.ksef.auth.KsefAuthService
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceEntity
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceRepository
import pl.detailing.crm.shared.StudioId
import java.time.OffsetDateTime

data class FetchExpensesCommand(
    val studioId: StudioId,
    val dateFrom: OffsetDateTime,
    val dateTo: OffsetDateTime,
    val pageSize: Int = 100
)

data class FetchExpensesResult(val fetched: Int, val skipped: Int)

@Service
class FetchKsefInvoicesHandler(
    private val ksefAuthService: KsefAuthService,
    private val ksefClient: KSeFClient,
    private val invoiceRepository: KsefInvoiceRepository,
    private val xmlParser: KsefInvoiceXmlParser
) {
    private val log = LoggerFactory.getLogger(FetchKsefInvoicesHandler::class.java)

    /**
     * Fetches EXPENSE (SUBJECT2) invoices from KSeF for the given date range.
     * Deduplicates by ksefNumber. Marks corrected invoices as CORRECTED when FA_KOR is detected.
     */
    @Transactional
    fun handle(command: FetchExpensesCommand): FetchExpensesResult {
        val pageSize = command.pageSize.coerceIn(10, 250)
        log.info("Fetching KSeF expenses studio={} from={} to={}", command.studioId, command.dateFrom, command.dateTo)

        val accessToken = ksefAuthService.getValidAccessToken(command.studioId)

        val filters = InvoiceQueryFilters().apply {
            subjectType = InvoiceQuerySubjectType.SUBJECT2
            dateRange   = InvoiceQueryDateRange(InvoiceQueryDateType.INVOICING, command.dateFrom, command.dateTo)
        }

        val allMetadata = fetchAllPages(filters, accessToken, pageSize)

        var fetched = 0
        var skipped = 0

        for (metadata in allMetadata) {
            if (invoiceRepository.existsByStudioIdAndKsefNumber(command.studioId.value, metadata.ksefNumber)) {
                skipped++
                continue
            }

            val isCorrection = metadata.invoiceType?.value == "FA_KOR"
            val xmlData = safeParseXml(metadata.ksefNumber, accessToken)

            invoiceRepository.save(
                KsefInvoiceEntity(
                    studioId       = command.studioId.value,
                    source         = "KSEF",
                    ksefNumber     = metadata.ksefNumber,
                    invoiceNumber  = metadata.invoiceNumber,
                    invoicingDate  = metadata.invoicingDate,
                    issueDate      = metadata.issueDate,
                    sellerNip      = metadata.seller?.nip,
                    sellerName     = xmlData.sellerName,
                    buyerNip       = metadata.buyer?.identifier?.value,
                    buyerName      = xmlData.buyerName,
                    netAmount      = metadata.netAmount,
                    grossAmount    = metadata.grossAmount,
                    vatAmount      = metadata.vatAmount,
                    currency       = metadata.currency,
                    invoiceType    = metadata.invoiceType?.value,
                    direction      = "EXPENSE",
                    isCorrection   = isCorrection,
                    status         = "ACTIVE",
                    paymentStatus  = "PENDING",
                    paymentForm    = xmlData.paymentForm?.name
                )
            )
            fetched++
        }

        log.info("KSeF fetch complete studio={}: fetched={} skipped={}", command.studioId, fetched, skipped)
        return FetchExpensesResult(fetched, skipped)
    }

    private fun safeParseXml(ksefNumber: String, accessToken: String): KsefXmlData {
        return try {
            val xml: ByteArray = ksefClient.getInvoice(ksefNumber, accessToken)
            xmlParser.parseInvoiceData(xml)
        } catch (e: Exception) {
            log.warn("Failed to fetch XML for invoice {}: {}", ksefNumber, e.message)
            KsefXmlData(null, null, null)
        }
    }

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
