package pl.detailing.crm.ksef.revenue.pdf

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.ksef.domain.PaymentForm
import pl.detailing.crm.ksef.qr.KsefQrCodeUrlBuilder
import pl.detailing.crm.ksef.revenue.domain.RevenueInvoiceType
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceEntity
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceItemRepository
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import pl.detailing.crm.shared.AmountInWords
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.logo.CompanyLogoService
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Gotowy dokument: bajty PDF-a i nazwa pliku do pobrania. */
data class InvoicePdfFile(val bytes: ByteArray, val fileName: String)

/**
 * Składa wizualizację faktury z danych ledgera przychodowego.
 *
 * Dokument powstaje na żądanie i nie jest zapisywany: oryginałem faktury jest dokument
 * ustrukturyzowany w KSeF, a PDF to jego odwzorowanie. Trzymanie kopii pliku obok
 * oryginału tworzyłoby drugie źródło prawdy, które zaraz się rozjedzie.
 *
 * Kwoty przechodzą z faktury bez jednego przeliczenia. Wizualizacja ma odwzorowywać
 * dane merytoryczne z KSeF, a nie liczyć je ponownie — przeliczenie „dla pewności"
 * jest tu dokładnie tym błędem, przed którym ostrzega CLAUDE.md §1.
 */
@Service
class InvoicePdfService(
    private val invoiceRepository: KsefRevenueInvoiceRepository,
    private val itemRepository: KsefRevenueInvoiceItemRepository,
    private val studioSettingsRepository: StudioSettingsRepository,
    private val companyLogoService: CompanyLogoService,
    private val qrCodeUrlBuilder: KsefQrCodeUrlBuilder,
    private val qrCodeImageFactory: QrCodeImageFactory,
    private val renderer: InvoicePdfRenderer
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }

    @Transactional(readOnly = true)
    fun render(studioId: StudioId, invoiceId: UUID): InvoicePdfFile {
        val invoice = invoiceRepository.findByIdAndStudioId(invoiceId, studioId.value)
            ?: throw NotFoundException("Faktura przychodowa $invoiceId nie istnieje")

        val items = itemRepository.findByInvoiceIdOrderByLineNumberAsc(invoiceId)
        val settings = studioSettingsRepository.findById(studioId.value).orElse(null)

        val lines = items.map {
            InvoiceLine(
                lineNumber = it.lineNumber,
                name = it.name,
                unit = it.unit,
                quantity = it.quantity,
                unitPriceNet = it.unitPriceNet,
                netValue = it.netValue,
                vatValue = it.vatValue,
                grossValue = it.grossValue,
                vatRate = it.vatRate
            )
        }

        val isCorrection = invoice.invoiceType == RevenueInvoiceType.KOR

        // Kod weryfikacyjny budujemy WYŁĄCZNIE dla faktury, która ma numer KSeF. Skrót XML-a
        // istnieje już przed wysyłką, więc bez tego warunku kod QR trafiałby na dokument,
        // którego KSeF nie zna — po zeskanowaniu prowadziłby donikąd.
        val verificationUrl = invoice.ksefNumber?.takeIf { it.isNotBlank() }?.let {
            qrCodeUrlBuilder.buildInvoiceVerificationUrl(
                sellerNip = invoice.sellerNip,
                issueDate = invoice.issueDate,
                invoiceHash = invoice.invoiceHash ?: invoice.invoiceXml?.let { xml -> qrCodeUrlBuilder.hashInvoiceXml(xml) }
            )
        }

        val data = InvoicePdfData(
            title = if (isCorrection) "FAKTURA KORYGUJĄCA" else "FAKTURA",
            invoiceNumber = invoice.invoiceNumber,
            issueDate = invoice.issueDate.format(DATE),
            // Datę sprzedaży podaje się, gdy różni się od daty wystawienia
            // (art. 106e ust. 1 pkt 6) — równą pomijamy, żeby nie dublować pola.
            saleDate = invoice.saleDate?.takeIf { it != invoice.issueDate }?.format(DATE),
            paymentDueDate = invoice.paymentDueDate?.format(DATE),
            seller = InvoiceParty(
                name = invoice.sellerName.orEmpty().ifBlank { settings?.name.orEmpty() },
                addressLine1 = invoice.sellerAddressLine1,
                addressLine2 = invoice.sellerAddressLine2,
                nip = invoice.sellerNip,
                countryCode = invoice.sellerCountryCode
            ),
            buyer = InvoiceParty(
                name = invoice.buyerName.orEmpty().ifBlank { "Nabywca nieznany" },
                addressLine1 = invoice.buyerAddressLine1,
                addressLine2 = invoice.buyerAddressLine2,
                nip = invoice.buyerNip,
                countryCode = invoice.buyerCountryCode
            ),
            lines = lines,
            vatBuckets = buckets(lines),
            totalNet = invoice.totalNet,
            totalVat = invoice.totalVat,
            totalGross = invoice.totalGross,
            currency = invoice.currency,
            totalInWords = AmountInWords.format(invoice.totalGross),
            paymentFormLabel = invoice.paymentForm?.let {
                runCatching { PaymentForm.valueOf(it).displayName }.getOrNull() ?: it
            },
            paymentStatusLabel = paymentStatusLabel(invoice.paymentStatus),
            bankAccount = invoice.sellerBankAccount ?: settings?.bankAccount,
            correctedInvoiceNumber = if (isCorrection) invoice.originalKsefNumber ?: originalNumber(invoice) else null,
            correctionReason = invoice.correctionReason,
            ksefNumber = invoice.ksefNumber,
            verificationQrPng = verificationUrl?.let { qrCodeImageFactory.png(it) },
            providerName = settings?.name?.trim().orEmpty().ifBlank { invoice.sellerName.orEmpty() },
            providerAddress = providerAddress(settings),
            contactLine = contactLine(settings),
            logoPng = loadLogo(studioId)
        )

        val bytes = renderer.render(data)
        logger.info(
            "Invoice PDF rendered: invoice={} lines={} ksefNumber={} bytes={}",
            invoice.invoiceNumber, lines.size, invoice.ksefNumber ?: "-", bytes.size
        )
        return InvoicePdfFile(bytes, "faktura-${asciiSlug(invoice.invoiceNumber)}.pdf")
    }

    /**
     * Sumy netto i VAT w podziale na stawki — art. 106e ust. 1 pkt 13-14.
     *
     * Grupujemy WARTOŚCI POZYCJI, nie przeliczamy ich od nowa: to jedyny sposób, żeby
     * podsumowanie zgadzało się co do grosza z tabelą nad nim i z fakturą w KSeF.
     */
    private fun buckets(lines: List<InvoiceLine>): List<InvoiceVatBucket> =
        lines.groupBy { it.vatRate.trim() }
            .map { (rate, group) ->
                InvoiceVatBucket(
                    vatRate = rate,
                    net = group.sumOf { it.netValue },
                    vat = group.sumOf { it.vatValue },
                    gross = group.sumOf { it.grossValue }
                )
            }
            .sortedByDescending { it.net }

    private fun originalNumber(invoice: KsefRevenueInvoiceEntity): String? =
        invoice.originalInvoiceId?.let { id ->
            invoiceRepository.findById(id).orElse(null)?.invoiceNumber
        }

    private fun paymentStatusLabel(status: String): String? = when (status.uppercase()) {
        "PAID" -> "Zapłacona"
        "PENDING" -> "Oczekuje na zapłatę"
        "OVERDUE" -> "Po terminie"
        else -> null
    }

    private fun providerAddress(settings: StudioSettingsEntity?): String? {
        val street = settings?.street?.trim().orEmpty()
        val cityLine = listOfNotNull(
            settings?.postalCode?.trim()?.takeIf { it.isNotBlank() },
            settings?.city?.trim()?.takeIf { it.isNotBlank() }
        ).joinToString(" ")
        return listOf(street, cityLine).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }
    }

    private fun contactLine(settings: StudioSettingsEntity?): String? = listOfNotNull(
        settings?.phone?.trim()?.takeIf { it.isNotBlank() },
        settings?.email?.trim()?.takeIf { it.isNotBlank() },
        settings?.website?.trim()?.takeIf { it.isNotBlank() }
    ).joinToString(", ").takeIf { it.isNotBlank() }

    private fun loadLogo(studioId: StudioId): ByteArray? = runCatching {
        companyLogoService.loadDocumentLogo(studioId.value)?.printPng
    }.onFailure {
        logger.warn("Nie udało się wczytać logo studia na fakturę: ${it.message}")
    }.getOrNull()

    /** Nazwa pliku idzie w nagłówku HTTP, a numer faktury niesie ukośniki. */
    private fun asciiSlug(value: String): String =
        value.lowercase()
            .map { ch -> if (ch.isLetterOrDigit() && ch.code < 128) ch else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "faktura" }
}
