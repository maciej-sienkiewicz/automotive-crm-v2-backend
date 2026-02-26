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
import pl.detailing.crm.ksef.domain.PaymentForm
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceEntity
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceRepository
import pl.detailing.crm.shared.StudioId
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
    private val invoiceRepository: KsefInvoiceRepository,
    private val xmlParser: KsefInvoiceXmlParser
) {
    private val log = LoggerFactory.getLogger(FetchKsefInvoicesHandler::class.java)

    /**
     * Pobiera metadane faktur z KSeF i zapisuje nowe lokalnie.
     *
     * Logika kategoryzacji:
     * - SUBJECT1 → direction = INCOME  (studio jest sprzedawcą – przychód)
     * - SUBJECT2 → direction = EXPENSE (studio jest nabywcą   – koszt)
     *
     * Korekty (FA_KOR) są oznaczane flagą isCorrection=true.
     * Po zapisaniu korekty automatycznie aktualizowany jest status faktury korygowanej
     * (jeśli znamy jej ksefNumber z metadanych SDK).
     *
     * Wszystkie strony są pobierane automatycznie (pagination loop).
     */
    @Transactional
    fun handle(command: FetchKsefInvoicesCommand): FetchKsefInvoicesResult {
        val effectivePageSize = command.pageSize.coerceIn(10, 250)
        val direction = directionFor(command.subjectType)

        log.info(
            "Fetching KSeF invoices for studio={} direction={} from={} to={}",
            command.studioId, direction, command.dateFrom, command.dateTo
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

            val isCorrection = metadata.invoiceType?.value == "FA_KOR"

            // originalKsefNumber: KSeF API v2 nie zwraca tej informacji w metadanych –
            // jest dostępna tylko w pełnym XML faktury (TODO dla przyszłej implementacji).
            val originalKsefNumber: String? = null

            val paymentForm = fetchPaymentForm(metadata.ksefNumber, accessToken)

            val entity = KsefInvoiceEntity(
                studioId = command.studioId.value,
                ksefNumber = metadata.ksefNumber,
                invoiceNumber = metadata.invoiceNumber,
                invoicingDate = metadata.invoicingDate,
                issueDate = metadata.issueDate,
                sellerNip = metadata.seller?.nip,
                buyerNip = metadata.buyer?.identifier?.value,
                netAmount = metadata.netAmount,
                grossAmount = metadata.grossAmount,
                vatAmount = metadata.vatAmount,
                currency = metadata.currency,
                invoiceType = metadata.invoiceType?.value,
                direction = direction,
                isCorrection = isCorrection,
                originalKsefNumber = originalKsefNumber,
                status = "ACTIVE",
                paymentForm = paymentForm?.name
            )
            val saved = invoiceRepository.save(entity)
            savedInvoices.add(saved.toDomain())
            fetchedCount++

            // Jeśli to korekta i znamy numer korygowanej faktury – zaktualizuj jej status
            if (isCorrection && originalKsefNumber != null) {
                invoiceRepository.updateStatus(command.studioId.value, originalKsefNumber, "CORRECTED")
                log.debug("Marked invoice {} as CORRECTED due to FA_KOR {}", originalKsefNumber, metadata.ksefNumber)
            }
        }

        log.info(
            "KSeF fetch complete for studio={} direction={}: fetched={} skipped={}",
            command.studioId, direction, fetchedCount, skippedCount
        )

        return FetchKsefInvoicesResult(
            fetched = fetchedCount,
            skipped = skippedCount,
            invoices = savedInvoices
        )
    }

    /**
     * Pobiera pełny XML faktury i wyciąga formę płatności.
     *
     * Błędy sieciowe / parsowania są obsługiwane gracefully – zwracamy null
     * zamiast przerywać cały import faktur z powodu jednego nieudanego pobrania.
     */
    private fun fetchPaymentForm(ksefNumber: String, accessToken: String): PaymentForm? {
        return try {
            val invoiceXml: ByteArray = ksefClient.getInvoice(ksefNumber, accessToken)
            xmlParser.parsePaymentForm(invoiceXml).also { form ->
                log.debug("Invoice {} paymentForm={}", ksefNumber, form)
            }
        } catch (e: Exception) {
            log.warn("Nie udało się pobrać pełnego XML dla faktury {}: {}", ksefNumber, e.message)
            null
        }
    }

    private fun directionFor(subjectType: InvoiceQuerySubjectType): String = when (subjectType) {
        InvoiceQuerySubjectType.SUBJECT1 -> "INCOME"
        else -> "EXPENSE"
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
