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

    companion object {
        /**
         * Limity KSeF API 2.0 dla GET /invoices/ksef/{ksefNumber}: 8 req/s, 16 req/min, 64 req/h
         * (naliczane per kontekst NIP + adres IP, w przesuwającym się oknie czasowym).
         *
         * Pacing 4s między pobraniami XML daje maks. 15 req/min — poniżej limitu minutowego.
         * Batch 12 faktur na przebieg przy syncu co 15 min to maks. 48 pobrań/h z backfillu,
         * co zostawia zapas na XML nowych faktur w ramach limitu godzinowego 64 req/h.
         */
        private const val BACKFILL_BATCH_SIZE = 12
        private const val XML_FETCH_INTERVAL_MS = 4_000L

        /** Maks. liczba ponowień po 429 dla jednego pobrania XML. */
        private const val MAX_RATE_LIMIT_RETRIES = 2

        /**
         * Gdy Retry-After przekracza ten próg, blokada dotyczy limitu minutowego/godzinowego —
         * nie czekamy aktywnie, tylko przerywamy przebieg (reszta w kolejnym cyklu syncu).
         */
        private const val MAX_RETRY_AFTER_SECONDS = 15L

        /** Domyślny czas oczekiwania po 429, gdy nie udało się odczytać Retry-After. */
        private const val DEFAULT_RETRY_AFTER_SECONDS = 2L

        /** Wyciąga sugerowany czas oczekiwania z komunikatu 429 KSeF („Spróbuj ponownie po N sekundach"). */
        private val RETRY_AFTER_PATTERN = Regex("po (\\d+) sekund")
    }

    /**
     * Wyczerpanie limitu żądań KSeF, którego nie opłaca się przeczekiwać w bieżącym przebiegu.
     * Faktury bez pobranego XML mają details_synced = FALSE i zostaną dokończone w kolejnym cyklu.
     */
    private class KsefRateLimitException(message: String) : RuntimeException(message)

    /** Znacznik ostatniego pobrania XML — wymusza odstęp [XML_FETCH_INTERVAL_MS] między żądaniami. */
    @Volatile
    private var lastXmlFetchAtMs = 0L

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
                safeParseXml(metadata.ksefNumber, accessToken)
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
    fun backfillMissingDetails(studioId: StudioId, batchSize: Int = BACKFILL_BATCH_SIZE): Int {
        val candidates = invoiceRepository.findByStudioIdAndSourceAndDetailsSyncedFalseOrderByFetchedAtDesc(
            studioId.value, "KSEF", PageRequest.of(0, batchSize)
        )
        if (candidates.isEmpty()) return 0

        val accessToken = ksefAuthService.getValidAccessToken(studioId)
        var backfilled = 0

        for (invoice in candidates) {
            val xmlData = try {
                safeParseXml(invoice.ksefNumber, accessToken) ?: continue
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
                quantity     = line.quantity,
                unitPriceNet = line.unitPriceNet,
                netValue     = line.netValue,
                grossValue   = line.grossValue,
                vatRate      = line.vatRate
            )
        })
    }

    /**
     * Pobiera i parsuje XML faktury z poszanowaniem limitów KSeF:
     * - wymusza minimalny odstęp między żądaniami ([XML_FETCH_INTERVAL_MS]),
     * - po 429 ponawia z czasem z Retry-After (maks. [MAX_RATE_LIMIT_RETRIES] razy),
     * - gdy blokada jest dłuższa niż [MAX_RETRY_AFTER_SECONDS] lub ponowienia wyczerpane,
     *   rzuca [KsefRateLimitException] — sygnał do przerwania przebiegu.
     *
     * @return null gdy pobranie/parsowanie nie powiodło się z przyczyn innych niż rate limit
     */
    private fun safeParseXml(ksefNumber: String, accessToken: String): KsefXmlData? {
        var attempt = 0
        while (true) {
            paceXmlFetch()
            try {
                val xml: ByteArray = ksefClient.getInvoice(ksefNumber, accessToken)
                return xmlParser.parseInvoiceData(xml)
            } catch (e: Exception) {
                if (!isRateLimited(e)) {
                    log.warn("Failed to fetch XML for invoice {}: {}", ksefNumber, e.message)
                    return null
                }

                val retryAfter = retryAfterSeconds(e)
                attempt++
                if (attempt > MAX_RATE_LIMIT_RETRIES || retryAfter > MAX_RETRY_AFTER_SECONDS) {
                    throw KsefRateLimitException(
                        "429 dla faktury $ksefNumber (retryAfter=${retryAfter}s, próba $attempt)"
                    )
                }
                log.info(
                    "KSeF 429 dla faktury {} — ponowienie za {}s (próba {}/{})",
                    ksefNumber, retryAfter, attempt, MAX_RATE_LIMIT_RETRIES
                )
                Thread.sleep(retryAfter * 1000)
            }
        }
    }

    /** Wymusza minimalny odstęp między pobraniami XML — limit KSeF to 8 req/s i 16 req/min. */
    private fun paceXmlFetch() {
        val waitMs = lastXmlFetchAtMs + XML_FETCH_INTERVAL_MS - System.currentTimeMillis()
        if (waitMs > 0) Thread.sleep(waitMs)
        lastXmlFetchAtMs = System.currentTimeMillis()
    }

    /**
     * KSeF zwraca 429 z opisem w treści błędu; SDK opakowuje odpowiedź w wyjątek
     * z komunikatem zawierającym kod statusu, stąd detekcja po treści komunikatu.
     */
    private fun isRateLimited(e: Exception): Boolean =
        e.message?.contains("429") == true

    /**
     * Czas oczekiwania sugerowany przez KSeF. Nagłówek Retry-After nie jest dostępny
     * w wyjątku SDK, ale ta sama wartość występuje w opisie błędu
     * („Przekroczono limit ... Spróbuj ponownie po N sekundach.").
     */
    private fun retryAfterSeconds(e: Exception): Long =
        RETRY_AFTER_PATTERN.find(e.message ?: "")
            ?.groupValues?.get(1)?.toLongOrNull()
            ?: DEFAULT_RETRY_AFTER_SECONDS

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
