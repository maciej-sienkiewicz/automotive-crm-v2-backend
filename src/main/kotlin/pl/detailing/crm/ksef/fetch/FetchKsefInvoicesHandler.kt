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
import pl.detailing.crm.ksef.domain.KsefInvoice
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceEntity
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.OffsetDateTime

data class FetchKsefInvoicesCommand(
    val studioId: StudioId,
    val dateFrom: OffsetDateTime,
    val dateTo: OffsetDateTime,
    val dateType: InvoiceQueryDateType = InvoiceQueryDateType.INVOICING,
    val subjectType: InvoiceQuerySubjectType = InvoiceQuerySubjectType.SUBJECT1,
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
    private val ksefClient: KSeFClient,
    private val invoiceRepository: KsefInvoiceRepository
) {
    private val log = LoggerFactory.getLogger(FetchKsefInvoicesHandler::class.java)

    /**
     * Fetches invoice metadata from KSeF using the official SDK and persists new invoices.
     * Invoices already present in the database (by ksefNumber + studioId) are skipped.
     * All pages are automatically fetched.
     */
    @Transactional
    fun handle(command: FetchKsefInvoicesCommand): FetchKsefInvoicesResult {
        val effectivePageSize = command.pageSize.coerceIn(10, 250)

        log.info(
            "Fetching KSeF invoices for studio={} from={} to={} subjectType={}",
            command.studioId, command.dateFrom, command.dateTo, command.subjectType
        )

        val accessToken = ksefAuthService.getValidAccessToken(command.studioId)

        val filters = InvoiceQueryFilters().apply {
            subjectType = command.subjectType
            dateRange = InvoiceQueryDateRange(command.dateType, command.dateFrom, command.dateTo)
        }

        val allMetadata = fetchAllPages(filters, accessToken, effectivePageSize)

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
                invoiceType = metadata.invoiceType?.value
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
        filters: InvoiceQueryFilters,
        accessToken: String,
        pageSize: Int
    ) = buildList {
        var pageOffset = 0
        var hasMore = true

        while (hasMore) {
            val response = ksefClient.queryInvoiceMetadata(
                pageOffset,
                pageSize,
                SortOrder.ASC,
                filters,
                accessToken
            )
            addAll(response.invoices)
            hasMore = response.hasMore == true && response.invoices.isNotEmpty()
            pageOffset += pageSize
        }
    }
}
