package pl.detailing.crm.ksef.revenue.sync

import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
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
import pl.detailing.crm.ksef.domain.PaymentForm
import pl.detailing.crm.ksef.fetch.KsefInvoiceXmlFetcher
import pl.detailing.crm.ksef.fetch.KsefRateLimitException
import pl.detailing.crm.ksef.fetch.KsefXmlData
import pl.detailing.crm.ksef.fetch.KsefXmlLine
import pl.detailing.crm.ksef.metrics.KsefTenantContext
import pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus
import pl.detailing.crm.ksef.revenue.domain.PriceMode
import pl.detailing.crm.ksef.revenue.domain.RevenueInvoiceType
import pl.detailing.crm.ksef.revenue.domain.RevenueSource
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceEntity
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceItemEntity
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceItemRepository
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import pl.detailing.crm.shared.StudioId
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

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
 * Metadane zapytania niosą kwoty, strony i typ dokumentu, ale nie pozycje ani
 * adresów i płatności — te są tylko w XML, pobieranym osobnym, ostro limitowanym
 * żądaniem. Bierzemy go od razu dla nowych faktur, a gdy budżet się wyczerpie,
 * rekord zostaje z detailsSynced = false i uzupełnia go [backfillMissingDetails].
 * Bez tego podgląd faktury zewnętrznej pokazywał sam nagłówek i pustą listę pozycji.
 */
@Service
class FetchRevenueInvoicesHandler(
    private val ksefClient: KSeFClient,
    private val authService: KsefAuthService,
    private val repository: KsefRevenueInvoiceRepository,
    private val itemRepository: KsefRevenueInvoiceItemRepository,
    private val xmlFetcher: KsefInvoiceXmlFetcher,
    private val duplicateDetector: RevenueDuplicateDetector
) {
    private val log = LoggerFactory.getLogger(FetchRevenueInvoicesHandler::class.java)

    companion object {
        /**
         * Ile faktur bez szczegółów uzupełniamy w jednym przebiegu. Budżet pobrań XML
         * dzielimy z fakturami kosztowymi (zob. [KsefInvoiceXmlFetcher]), więc batch
         * jest mniejszy niż sam limit KSeF — reszta doczeka kolejnego cyklu syncu.
         */
        private const val BACKFILL_BATCH_SIZE = 6
    }

    @Transactional
    fun handle(command: FetchRevenueCommand): FetchRevenueResult =
        KsefTenantContext.withStudio(command.studioId) { doHandle(command) }

    private fun doHandle(command: FetchRevenueCommand): FetchRevenueResult {
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
        var rateLimited = false

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

            // Po wyczerpaniu budżetu zapisujemy fakturę z samych metadanych —
            // pozycje i szczegóły dołoży synchronizacja wsteczna w kolejnym cyklu
            val xmlData = if (rateLimited) null else try {
                xmlFetcher.fetch(command.studioId, metadata.ksefNumber, accessToken)
            } catch (e: KsefRateLimitException) {
                log.warn("KSeF rate limit podczas pullu przychodów studio={}: {}", command.studioId, e.message)
                rateLimited = true
                null
            }

            val external = toExternalEntity(command.studioId, metadata, xmlData)
            repository.save(external)
            saveItems(external.id, xmlData?.lines.orEmpty())
            fetched++

            if (duplicateDetector.checkExternalInvoice(external)) duplicates++
        }

        log.info(
            "KSeF revenue fetch complete studio={}: external={} matched={} duplicatesSuspected={}",
            command.studioId, fetched, matched, duplicates
        )
        return FetchRevenueResult(fetched, matched, duplicates)
    }

    /**
     * Synchronizacja wsteczna: faktury zewnętrzne zapisane bez XML (bo wyczerpał się
     * budżet żądań albo pobranie padło) dostają pozycje i szczegóły w kolejnym cyklu.
     * Sterowana lokalną bazą, nie oknem dat pullu, więc obejmuje też faktury sprzed
     * wprowadzenia pobierania XML — to one mają dziś pustą listę pozycji.
     */
    @Transactional
    fun backfillMissingDetails(studioId: StudioId, batchSize: Int = BACKFILL_BATCH_SIZE): Int =
        KsefTenantContext.withStudio(studioId) { doBackfill(studioId, batchSize) }

    private fun doBackfill(studioId: StudioId, batchSize: Int): Int {
        val candidates = repository.findExternalMissingDetails(studioId.value, PageRequest.of(0, batchSize))
        if (candidates.isEmpty()) return 0

        val accessToken = authService.getValidAccessToken(studioId)
        var backfilled = 0

        for (invoice in candidates) {
            val ksefNumber = invoice.ksefNumber ?: continue
            val xmlData = try {
                xmlFetcher.fetch(studioId, ksefNumber, accessToken) ?: continue
            } catch (e: KsefRateLimitException) {
                log.warn("KSeF rate limit podczas backfillu przychodów studio={}, przerywam: {}", studioId, e.message)
                break
            }

            if (itemRepository.findByInvoiceIdOrderByLineNumberAsc(invoice.id).isEmpty()) {
                saveItems(invoice.id, xmlData.lines)
            }
            invoice.applyXmlDetails(
                saleDate           = xmlData.saleDate,
                sellerAddressLine1 = xmlData.seller.addressLine1,
                sellerAddressLine2 = xmlData.seller.addressLine2,
                buyerAddressLine1  = xmlData.buyer.addressLine1,
                buyerAddressLine2  = xmlData.buyer.addressLine2,
                bankAccount        = xmlData.payment.bankAccount,
                paymentForm        = xmlData.payment.paymentForm?.name,
                paymentDueDate     = xmlData.payment.dueDate
            )
            repository.save(invoice)
            backfilled++
        }

        log.info("KSeF revenue backfill studio={}: candidates={} backfilled={}", studioId, candidates.size, backfilled)
        return backfilled
    }

    // ── Private ────────────────────────────────────────────────────────────────

    /**
     * Pozycje faktury zewnętrznej. XML KSeF podaje kwoty w złotych, nasz ledger
     * trzyma grosze — konwersja przez BigDecimal, nigdy przez arytmetykę Double,
     * żeby 0.1 + 0.2 nie zamieniło się w grosz różnicy na fakturze.
     */
    private fun saveItems(invoiceId: UUID, lines: List<KsefXmlLine>) {
        if (lines.isEmpty()) return

        itemRepository.saveAll(
            lines.map { line ->
                val netValue = toGrosz(line.netValue)
                // Brak P_11A (faktura liczona od netto, stawka nieznana parserowi)
                // znaczy tyle, że brutto równa się netto — nie że wiersz jest zerowy
                val grossValue = line.grossValue?.let { toGrosz(it) } ?: netValue
                KsefRevenueInvoiceItemEntity(
                    invoiceId    = invoiceId,
                    lineNumber   = line.lineNumber,
                    name         = line.name?.take(500) ?: "Pozycja ${line.lineNumber}",
                    unit         = line.unit?.take(20),
                    quantity     = line.quantity?.let { BigDecimal.valueOf(it).setScale(3, RoundingMode.HALF_UP) }
                        ?: BigDecimal.ONE,
                    unitPriceNet = toGrosz(line.unitPriceNet),
                    priceMode    = PriceMode.NET,
                    netValue     = netValue,
                    vatValue     = grossValue - netValue,
                    grossValue   = grossValue,
                    // P_12 jest w FA obowiązkowe; „np" (nie podlega) to najmniej mylący
                    // zapis dla wiersza, w którym stawki mimo to zabrakło
                    vatRate      = line.vatRate?.take(5) ?: "np"
                )
            }
        )
    }

    private fun toExternalEntity(
        studioId: StudioId,
        metadata: InvoiceMetadata,
        xmlData: KsefXmlData?
    ): KsefRevenueInvoiceEntity {
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
            invoiceHash = metadata.invoiceHash,
            saleDate = xmlData?.saleDate,
            sellerAddressLine1 = xmlData?.seller?.addressLine1,
            sellerAddressLine2 = xmlData?.seller?.addressLine2,
            sellerBankAccount = xmlData?.payment?.bankAccount,
            buyerAddressLine1 = xmlData?.buyer?.addressLine1,
            buyerAddressLine2 = xmlData?.buyer?.addressLine2,
            paymentForm = xmlData?.payment?.paymentForm?.name,
            paymentDueDate = xmlData?.payment?.dueDate,
            paymentStatus = resolvePaymentStatus(xmlData),
            detailsSynced = xmlData != null
        )
    }

    /**
     * Zaplacono=1 w XML oznacza fakturę już opłaconą — niezależnie od formy płatności.
     * Bez XML nie wiemy nic ponad to, że faktura istnieje, więc zostaje PENDING:
     * użytkownik oznaczy ją ręcznie, a backfill i tak nie rusza tego pola.
     */
    private fun resolvePaymentStatus(xmlData: KsefXmlData?): String {
        val payment = xmlData?.payment ?: return "PENDING"
        return if (payment.paid) "PAID" else PaymentForm.defaultPaymentStatus(payment.paymentForm)
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
