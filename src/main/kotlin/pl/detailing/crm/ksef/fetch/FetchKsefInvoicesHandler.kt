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
import pl.detailing.crm.ksef.domain.PaymentForm
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceEntity
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceItemEntity
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceItemRepository
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceRepository
import pl.detailing.crm.shared.StudioId
import java.time.OffsetDateTime
import java.util.UUID

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
    private val itemRepository: KsefInvoiceItemRepository,
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
            val existing = invoiceRepository.findByStudioIdAndKsefNumber(command.studioId.value, metadata.ksefNumber)
            if (existing != null) {
                backfillItemsIfMissing(existing, accessToken)
                skipped++
                continue
            }

            val isCorrection = metadata.invoiceType?.value == "FA_KOR"
            val xmlData = safeParseXml(metadata.ksefNumber, accessToken)

            val invoice = invoiceRepository.save(
                KsefInvoiceEntity(
                    studioId       = command.studioId.value,
                    source         = "KSEF",
                    ksefNumber     = metadata.ksefNumber,
                    invoiceNumber  = metadata.invoiceNumber,
                    invoicingDate  = metadata.invoicingDate,
                    issueDate      = metadata.issueDate,
                    sellerNip      = metadata.seller?.nip ?: xmlData.seller.nip,
                    sellerName     = xmlData.seller.name,
                    buyerNip       = metadata.buyer?.identifier?.value ?: xmlData.buyer.nip,
                    buyerName      = xmlData.buyer.name,
                    sellerAddressLine1 = xmlData.seller.addressLine1,
                    sellerAddressLine2 = xmlData.seller.addressLine2,
                    sellerCountryCode  = xmlData.seller.countryCode,
                    buyerAddressLine1  = xmlData.buyer.addressLine1,
                    buyerAddressLine2  = xmlData.buyer.addressLine2,
                    buyerCountryCode   = xmlData.buyer.countryCode,
                    netAmount      = metadata.netAmount,
                    grossAmount    = metadata.grossAmount,
                    vatAmount      = metadata.vatAmount,
                    currency       = metadata.currency,
                    invoiceType    = metadata.invoiceType?.value,
                    direction      = "EXPENSE",
                    isCorrection   = isCorrection,
                    status         = "ACTIVE",
                    paymentStatus  = resolvePaymentStatus(xmlData.payment),
                    paymentForm    = xmlData.payment.paymentForm?.name,
                    paymentDueDate = xmlData.payment.dueDate,
                    bankAccount    = xmlData.payment.bankAccount
                )
            )
            saveItems(invoice.id, xmlData.lines)
            fetched++
        }

        log.info("KSeF fetch complete studio={}: fetched={} skipped={}", command.studioId, fetched, skipped)
        return FetchExpensesResult(fetched, skipped)
    }

    /**
     * Faktury zsynchronizowane przed wprowadzeniem tabeli pozycji nie mają wierszy.
     * Ponowny fetch obejmujący ich zakres dat uzupełnia brakujące pozycje z XML,
     * nie modyfikując samej faktury (statusy i notatki admina zostają nietknięte).
     */
    private fun backfillItemsIfMissing(existing: KsefInvoiceEntity, accessToken: String) {
        if (existing.source != "KSEF") return
        if (itemRepository.existsByInvoiceId(existing.id)) return

        val xmlData = safeParseXml(existing.ksefNumber, accessToken)
        if (xmlData.lines.isNotEmpty()) {
            saveItems(existing.id, xmlData.lines)
            log.info("Backfilled {} items for existing invoice {}", xmlData.lines.size, existing.ksefNumber)
        }
    }

    /**
     * Zaplacono=1 w XML oznacza fakturę już opłaconą — niezależnie od formy płatności.
     * W pozostałych przypadkach status wynika z formy płatności (przelew/kredyt → PENDING).
     */
    private fun resolvePaymentStatus(payment: KsefXmlPayment): String =
        if (payment.paid) "PAID" else PaymentForm.defaultPaymentStatus(payment.paymentForm)

    private fun saveItems(invoiceId: UUID, lines: List<KsefXmlLine>) {
        if (lines.isEmpty()) return
        itemRepository.saveAll(lines.map { line ->
            KsefInvoiceItemEntity(
                invoiceId    = invoiceId,
                lineNumber   = line.lineNumber,
                name         = line.name,
                unit         = line.unit,
                quantity     = line.quantity,
                unitPriceNet = line.unitPriceNet,
                netValue     = line.netValue,
                grossValue   = line.grossValue,
                vatRate      = line.vatRate
            )
        })
    }

    private fun safeParseXml(ksefNumber: String, accessToken: String): KsefXmlData {
        return try {
            val xml: ByteArray = ksefClient.getInvoice(ksefNumber, accessToken)
            xmlParser.parseInvoiceData(xml)
        } catch (e: Exception) {
            log.warn("Failed to fetch XML for invoice {}: {}", ksefNumber, e.message)
            KsefXmlData.EMPTY
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
