package pl.detailing.crm.ksef.fetch

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.ksef.auth.KsefAuthService
import pl.detailing.crm.ksef.client.KsefApiClient
import pl.detailing.crm.ksef.client.KsefInvoiceQueryDateRange
import pl.detailing.crm.ksef.client.KsefInvoiceQueryFilters
import pl.detailing.crm.ksef.domain.KsefInvoice
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceEntity
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceRepository
import pl.detailing.crm.shared.StudioId
import java.time.OffsetDateTime

data class FetchKsefInvoicesCommand(
    val studioId: StudioId,
    val dateFrom: OffsetDateTime,
    val dateTo: OffsetDateTime,
    val dateType: String = "InvoicingDate",  // InvoicingDate | AcquisitionDate | IssueDate
    val subjectType: String = "Subject1",    // Subject1 = seller, Subject2 = buyer, Subject3 = other
    val pageSize: Int = 50
)

data class FetchKsefInvoicesResult(
    val fetched: Int,
    val skipped: Int,
    val invoices: List<KsefInvoice>
)

@Service
class FetchKsefInvoicesHandler(
    private val ksefAuthService: KsefAuthService,
    private val ksefApiClient: KsefApiClient,
    private val invoiceRepository: KsefInvoiceRepository
) {
    private val log = LoggerFactory.getLogger(FetchKsefInvoicesHandler::class.java)

    /**
     * Fetches invoice metadata from KSeF for the given date range and persists new invoices.
     * Invoices already present in the database (by ksefNumber) are skipped.
     * Pagination is handled automatically by fetching all pages.
     */
    @Transactional
    fun handle(command: FetchKsefInvoicesCommand): FetchKsefInvoicesResult {
        log.info(
            "Fetching KSeF invoices for studio={} from={} to={} subjectType={}",
            command.studioId, command.dateFrom, command.dateTo, command.subjectType
        )

        val accessToken = ksefAuthService.getValidAccessToken(command.studioId)

        val filters = KsefInvoiceQueryFilters(
            subjectType = command.subjectType,
            dateRange = KsefInvoiceQueryDateRange(
                type = command.dateType,
                from = command.dateFrom,
                to = command.dateTo
            )
        )

        val allMetadata = fetchAllPages(filters, accessToken, command.pageSize)

        var fetchedCount = 0
        var skippedCount = 0
        val savedInvoices = mutableListOf<KsefInvoice>()

        for (metadata in allMetadata) {
            if (invoiceRepository.existsByStudioIdAndKsefNumber(command.studioId.value, metadata.ksefNumber)) {
                skippedCount++
                continue
            }

            val entity = KsefInvoiceEntity(
                studioId = command.studioId.value,
                ksefNumber = metadata.ksefNumber,
                invoiceNumber = metadata.invoiceNumber,
                invoicingDate = metadata.invoicingDate,
                issueDate = metadata.issueDate,
                sellerNip = metadata.seller?.identifier,
                buyerNip = metadata.buyer?.identifier,
                netAmount = metadata.netAmount,
                grossAmount = metadata.grossAmount,
                vatAmount = metadata.vatAmount,
                currency = metadata.currency,
                invoiceType = metadata.invoiceType
            )
            val saved = invoiceRepository.save(entity)
            savedInvoices.add(saved.toDomain())
            fetchedCount++
        }

        log.info(
            "KSeF fetch complete for studio={}: fetched={} skipped={}",
            command.studioId, fetchedCount, skippedCount
        )

        return FetchKsefInvoicesResult(
            fetched = fetchedCount,
            skipped = skippedCount,
            invoices = savedInvoices
        )
    }

    private fun fetchAllPages(
        filters: KsefInvoiceQueryFilters,
        accessToken: String,
        pageSize: Int
    ) = buildList {
        var pageOffset = 0
        var hasMore = true
        val effectivePageSize = pageSize.coerceIn(10, 250)

        while (hasMore) {
            val response = ksefApiClient.queryInvoiceMetadata(
                pageOffset = pageOffset,
                pageSize = effectivePageSize,
                filters = filters,
                accessToken = accessToken
            )
            addAll(response.invoices)
            hasMore = response.hasMore == true && response.invoices.isNotEmpty()
            pageOffset += effectivePageSize
        }
    }
}
