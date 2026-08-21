package pl.detailing.crm.ksef.fetch

import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
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
import pl.detailing.crm.ksef.metrics.KsefTenantContext
import pl.detailing.crm.shared.StudioId
import java.math.BigDecimal
import java.math.RoundingMode
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
    private val xmlFetcher: KsefInvoiceXmlFetcher
) {
    private val log = LoggerFactory.getLogger(FetchKsefInvoicesHandler::class.java)

    companion object {
        /**
         * Ile faktur bez szczegółów uzupełniamy w jednym przebiegu. Budżet pobrań XML
         * jest wspólny z przychodami (zob. [KsefInvoiceXmlFetcher]), więc batch jest
         * mniejszy niż sam limit KSeF — reszta doczeka kolejnego cyklu syncu.
         */
        private const val BACKFILL_BATCH_SIZE = 8
    }

    /**
     * Pobiera faktury kosztowe (SUBJECT2) z KSeF dla zadanego zakresu dat.
     * Deduplikuje po numerze KSeF, oznacza faktury skorygowane jako CORRECTED przy FA_KOR.
     *
     * Zakres dat ustala wyłącznie [pl.detailing.crm.ksef.sync.KsefSyncService], który
     * nigdy nie schodzi poniżej momentu podania tokenu. Nie ma i nie może być ścieżki
     * z dowolnym zakresem: pobranie historii sprzed integracji dublowałoby koszty już
     * wprowadzone ręcznie i cicho psuło statystyki.
     */
    @Transactional
    fun handle(command: FetchExpensesCommand): FetchExpensesResult =
        KsefTenantContext.withStudio(command.studioId) { doHandle(command) }

    private fun doHandle(command: FetchExpensesCommand): FetchExpensesResult {
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
        var rateLimited = false

        for (metadata in allMetadata) {
            val existing = invoiceRepository.findByStudioIdAndKsefNumber(command.studioId.value, metadata.ksefNumber)
            if (existing != null) {
                skipped++
                continue
            }

            val isCorrection = metadata.invoiceType?.value == "FA_KOR"
            // Po wyczerpaniu limitu żądań zapisujemy faktury z samych metadanych —
            // XML (pozycje, adresy, płatność) uzupełni backfill w kolejnym cyklu
            val parsedXml = if (rateLimited) null else try {
                xmlFetcher.fetch(command.studioId, metadata.ksefNumber, accessToken)
            } catch (e: KsefRateLimitException) {
                log.warn("KSeF rate limit podczas fetchu studio={}: {}", command.studioId, e.message)
                rateLimited = true
                null
            }
            val xmlData = parsedXml ?: KsefXmlData.EMPTY

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
                    netAmount      = toGrosz(metadata.netAmount),
                    grossAmount    = toGrosz(metadata.grossAmount),
                    vatAmount      = toGrosz(metadata.vatAmount),
                    currency       = metadata.currency,
                    invoiceType    = metadata.invoiceType?.value,
                    direction      = "EXPENSE",
                    isCorrection   = isCorrection,
                    status         = "ACTIVE",
                    paymentStatus  = resolvePaymentStatus(xmlData.payment),
                    paymentForm    = xmlData.payment.paymentForm?.name,
                    paymentDueDate = xmlData.payment.dueDate,
                    bankAccount    = xmlData.payment.bankAccount,
                    // Gdy pobranie XML się nie powiodło, faktura zostanie uzupełniona
                    // przez synchronizację wsteczną w kolejnym przebiegu
                    detailsSynced  = parsedXml != null
                )
            )
            saveItems(invoice.id, xmlData.lines)
            fetched++
        }

        log.info("KSeF fetch complete studio={}: fetched={} skipped={}", command.studioId, fetched, skipped)
        return FetchExpensesResult(fetched, skipped)
    }

    /**
     * Synchronizacja wsteczna: faktury zsynchronizowane przed wprowadzeniem pozycji
     * i szczegółów (details_synced = FALSE) mają uzupełniane brakujące dane z XML —
     * pozycje, adresy stron i szczegóły płatności. Pola kontrolowane przez admina
     * (status, paymentStatus, note) pozostają nietknięte.
     *
     * Sterowana lokalną bazą (nie oknem dat zapytania do KSeF), więc obejmuje każdą
     * wcześniej pobraną fakturę niezależnie od jej daty. XML pobierany bezpośrednio
     * po numerze KSeF; nieudane pobrania są ponawiane w kolejnych przebiegach.
     */
    @Transactional
    fun backfillMissingDetails(studioId: StudioId, batchSize: Int = BACKFILL_BATCH_SIZE): Int =
        KsefTenantContext.withStudio(studioId) { doBackfill(studioId, batchSize) }

    private fun doBackfill(studioId: StudioId, batchSize: Int): Int {
        val candidates = invoiceRepository.findByStudioIdAndSourceAndDetailsSyncedFalseOrderByFetchedAtDesc(
            studioId.value, "KSEF", PageRequest.of(0, batchSize)
        )
        if (candidates.isEmpty()) return 0

        val accessToken = ksefAuthService.getValidAccessToken(studioId)
        var backfilled = 0

        for (invoice in candidates) {
            val xmlData = try {
                xmlFetcher.fetch(studioId, invoice.ksefNumber, accessToken) ?: continue
            } catch (e: KsefRateLimitException) {
                // Limit żądań wyczerpany — pozostałe faktury zachowują details_synced=FALSE
                // i zostaną uzupełnione w kolejnym cyklu syncu
                log.warn("KSeF rate limit podczas backfillu studio={}, przerywam przebieg: {}", studioId, e.message)
                break
            }

            if (!itemRepository.existsByInvoiceId(invoice.id)) {
                saveItems(invoice.id, xmlData.lines)
            }
            invoiceRepository.save(
                invoice.withBackfilledDetails(
                    sellerNip          = xmlData.seller.nip,
                    sellerName         = xmlData.seller.name,
                    buyerNip           = xmlData.buyer.nip,
                    buyerName          = xmlData.buyer.name,
                    sellerAddressLine1 = xmlData.seller.addressLine1,
                    sellerAddressLine2 = xmlData.seller.addressLine2,
                    sellerCountryCode  = xmlData.seller.countryCode,
                    buyerAddressLine1  = xmlData.buyer.addressLine1,
                    buyerAddressLine2  = xmlData.buyer.addressLine2,
                    buyerCountryCode   = xmlData.buyer.countryCode,
                    paymentForm        = xmlData.payment.paymentForm?.name,
                    paymentDueDate     = xmlData.payment.dueDate,
                    bankAccount        = xmlData.payment.bankAccount
                )
            )
            backfilled++
        }

        log.info("KSeF backfill studio={}: candidates={} backfilled={}", studioId, candidates.size, backfilled)
        return backfilled
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
                quantity     = line.quantity?.let { BigDecimal.valueOf(it).setScale(3, RoundingMode.HALF_UP) },
                unitPriceNet = toGrosz(line.unitPriceNet),
                netValue     = toGrosz(line.netValue),
                grossValue   = toGrosz(line.grossValue),
                vatRate      = line.vatRate
            )
        })
    }

    /**
     * Złote z KSeF (i z XML) na grosze. Przez BigDecimal, nigdy przez arytmetykę
     * na Double: 45.45 * 100 na double daje 4544.999999999999.
     *
     * null zostaje nullem — brak kwoty w dokumencie to co innego niż kwota zero.
     */
    private fun toGrosz(amount: Double?): Long? =
        amount?.let { BigDecimal.valueOf(it).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong() }

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
