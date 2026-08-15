package pl.detailing.crm.ksef.revenue.issue

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus
import pl.detailing.crm.ksef.revenue.domain.PriceMode
import pl.detailing.crm.ksef.revenue.domain.RevenueInvoiceType
import pl.detailing.crm.ksef.revenue.domain.RevenueSource
import pl.detailing.crm.ksef.revenue.domain.VatRate
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceEntity
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceItemEntity
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceItemRepository
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import pl.detailing.crm.ksef.revenue.send.KsefRevenueDispatchService
import pl.detailing.crm.ksef.revenue.xml.CorrectedInvoiceRef
import pl.detailing.crm.ksef.revenue.xml.Fa3XmlBuilder
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/**
 * Pozycja korygująca — różnica względem faktury pierwotnej (metoda różnicowa).
 * Kwoty mogą być ujemne (zmniejszenie) lub dodatnie (zwiększenie).
 */
data class CorrectionItemCommand(
    val name: String,
    val unit: String? = "szt.",
    val quantity: BigDecimal = BigDecimal.ONE,
    /** Różnica ceny jednostkowej netto w groszach (może być ujemna). */
    val unitPriceNet: Long,
    val vatRate: String
)

data class IssueCorrectionCommand(
    val studioId: StudioId,
    val userId: UserId,
    val originalInvoiceId: UUID,
    val reason: String,
    /**
     * null → korekta pełna do zera (wiersze faktury pierwotnej z przeciwnym znakiem).
     * Podane → korekta różnicowa wg wskazanych pozycji.
     */
    val items: List<CorrectionItemCommand>? = null,
    val issueDate: LocalDate = LocalDate.now(),
    /** Podstawa prawna zwolnienia (P_19A) — wymagana, gdy korekta zawiera stawkę zw. */
    val exemptionLegalBasis: String? = null
)

/**
 * Wystawia fakturę korygującą (FA_KOR) do faktury przyjętej w KSeF.
 *
 * Zasady:
 * - korygować można wyłącznie fakturę ACCEPTED z nadanym numerem KSeF
 *   (dokument formalnie istnieje dopiero z numerem KSeF),
 * - jedna aktywna korekta na fakturę (odrzucona korekta nie blokuje kolejnej),
 * - korekta pełna neguje wiersze pierwotne — dostępna tylko dla faktur CRM
 *   (dla EXTERNAL nie znamy pozycji; wymagane jawne pozycje korygujące),
 * - jak przy fakturze pierwotnej: dokument utrwalany przed wysyłką, błąd łączności
 *   kieruje do kolejki retry.
 */
@Service
class IssueCorrectionHandler(
    private val invoiceRepository: KsefRevenueInvoiceRepository,
    private val itemRepository: KsefRevenueInvoiceItemRepository,
    private val xmlBuilder: Fa3XmlBuilder,
    private val dispatchService: KsefRevenueDispatchService,
    private val numberGenerator: RevenueInvoiceNumberGenerator
) {
    private val log = LoggerFactory.getLogger(IssueCorrectionHandler::class.java)

    companion object {
        const val CORRECTION_PREFIX = "FK"
    }

    fun handle(command: IssueCorrectionCommand): KsefRevenueInvoiceEntity {
        val correction = persistWithNumberRetry(command)
        return dispatchService.dispatch(correction)
    }

    /** Jak przy fakturze pierwotnej — kolizję numeru rozstrzyga indeks unikalny. */
    private fun persistWithNumberRetry(command: IssueCorrectionCommand): KsefRevenueInvoiceEntity {
        repeat(IssueRevenueInvoiceHandler.NUMBER_COLLISION_ATTEMPTS) { attempt ->
            try {
                return persistCorrection(command)
            } catch (e: DataIntegrityViolationException) {
                log.warn(
                    "Kolizja numeru korekty (próba {}/{}): {}",
                    attempt + 1, IssueRevenueInvoiceHandler.NUMBER_COLLISION_ATTEMPTS, e.mostSpecificCause.message
                )
            }
        }
        throw ValidationException("Nie udało się nadać unikalnego numeru korekty — spróbuj ponownie za chwilę")
    }

    @Transactional
    fun persistCorrection(command: IssueCorrectionCommand): KsefRevenueInvoiceEntity {
        if (command.reason.isBlank()) {
            throw ValidationException("Przyczyna korekty jest wymagana")
        }

        val original = invoiceRepository.findByIdAndStudioId(command.originalInvoiceId, command.studioId.value)
            ?: throw NotFoundException("Faktura ${command.originalInvoiceId} nie istnieje")

        val originalKsefNumber = original.ksefNumber
        if (original.ksefStatus != KsefRevenueStatus.ACCEPTED || originalKsefNumber == null) {
            throw ValidationException(
                "Korygować można tylko fakturę przyjętą w KSeF (status ACCEPTED z numerem KSeF). " +
                    "Bieżący status: ${original.ksefStatus}"
            )
        }
        if (original.invoiceType == RevenueInvoiceType.KOR) {
            throw ValidationException("Nie można korygować faktury korygującej — skoryguj fakturę pierwotną kolejną korektą")
        }
        val hasActiveCorrection = invoiceRepository.existsByStudioIdAndOriginalInvoiceIdAndKsefStatusNot(
            command.studioId.value, original.id, KsefRevenueStatus.REJECTED
        )
        if (hasActiveCorrection) {
            throw ValidationException("Do tej faktury istnieje już korekta (lub jest w trakcie wysyłki)")
        }

        val correctionRows = resolveCorrectionRows(command, original)
        if (correctionRows.isEmpty()) {
            throw ValidationException("Korekta musi mieć co najmniej jedną pozycję")
        }

        val totalNet = correctionRows.sumOf { it.netValue }
        val totalVat = correctionRows.sumOf { it.vatValue }
        val totalGross = correctionRows.sumOf { it.grossValue }

        // Numeracja korekt idzie po NIP-ie sprzedawcy — tak samo jak faktur
        val sellerNip = original.sellerNip
            ?: throw ValidationException(
                "Faktura pierwotna nie ma zapisanego NIP-u sprzedawcy — nie można nadać numeru korekty"
            )
        val correctionNumber = numberGenerator.next(sellerNip, CORRECTION_PREFIX, command.issueDate)

        val correction = KsefRevenueInvoiceEntity(
            studioId           = command.studioId.value,
            source             = RevenueSource.CRM,
            ksefStatus         = KsefRevenueStatus.PENDING,
            invoiceNumber      = correctionNumber,
            invoiceType        = RevenueInvoiceType.KOR,
            originalInvoiceId  = original.id,
            originalKsefNumber = originalKsefNumber,
            correctionReason   = command.reason.trim(),
            issueDate          = command.issueDate,
            saleDate           = original.saleDate,
            sellerNip          = original.sellerNip,
            sellerName         = original.sellerName,
            sellerAddressLine1 = original.sellerAddressLine1,
            sellerAddressLine2 = original.sellerAddressLine2,
            sellerCountryCode  = original.sellerCountryCode,
            sellerBankAccount  = original.sellerBankAccount,
            buyerNip           = original.buyerNip,
            buyerName          = original.buyerName,
            buyerAddressLine1  = original.buyerAddressLine1,
            buyerAddressLine2  = original.buyerAddressLine2,
            buyerCountryCode   = original.buyerCountryCode,
            buyerEmail         = original.buyerEmail,
            totalNet           = totalNet,
            totalVat           = totalVat,
            totalGross         = totalGross,
            paymentForm        = original.paymentForm,
            paymentStatus      = "PENDING",
            visitId            = original.visitId,
            customerId         = original.customerId,
            description        = "Korekta do ${original.invoiceNumber} ($originalKsefNumber)",
            createdBy          = command.userId.value
        )

        val items = correctionRows.mapIndexed { index, row ->
            KsefRevenueInvoiceItemEntity(
                invoiceId      = correction.id,
                lineNumber     = index + 1,
                name           = row.name,
                unit           = row.unit,
                quantity       = row.quantity,
                unitPriceNet   = row.unitPriceNet,
                unitPriceGross = row.unitPriceGross,
                priceMode      = row.priceMode,
                netValue       = row.netValue,
                vatValue       = row.vatValue,
                grossValue     = row.grossValue,
                vatRate        = row.vatRate
            )
        }

        correction.invoiceXml = xmlBuilder.build(
            invoice = correction,
            items = items,
            correctedInvoice = CorrectedInvoiceRef(
                issueDate     = original.issueDate,
                invoiceNumber = original.invoiceNumber,
                ksefNumber    = originalKsefNumber
            ),
            exemptionLegalBasis = command.exemptionLegalBasis
        )

        invoiceRepository.saveAndFlush(correction)
        itemRepository.saveAll(items)

        log.info(
            "Korekta utworzona: studio={} number={} original={} gross={}",
            command.studioId, correctionNumber, original.invoiceNumber, totalGross
        )
        return correction
    }

    // ── Private ────────────────────────────────────────────────────────────────

    private data class CorrectionRow(
        val name: String,
        val unit: String?,
        val quantity: BigDecimal,
        val unitPriceNet: Long,
        val unitPriceGross: Long? = null,
        val priceMode: PriceMode = PriceMode.NET,
        val netValue: Long,
        val vatValue: Long,
        val grossValue: Long,
        val vatRate: String
    )

    private fun resolveCorrectionRows(
        command: IssueCorrectionCommand,
        original: KsefRevenueInvoiceEntity
    ): List<CorrectionRow> {
        val explicit = command.items
        if (explicit != null) {
            return explicit.map { item ->
                if (item.name.isBlank()) throw ValidationException("Pozycja korekty: nazwa jest wymagana")
                val rate = runCatching { VatRate.fromCode(item.vatRate) }
                    .getOrElse { throw ValidationException(it.message ?: "Nieprawidłowa stawka VAT") }
                val net = BigDecimal(item.unitPriceNet)
                    .multiply(item.quantity)
                    .setScale(0, RoundingMode.HALF_UP)
                    .toLong()
                val vat = signedVat(rate, net)
                CorrectionRow(
                    name = item.name.trim(), unit = item.unit, quantity = item.quantity,
                    unitPriceNet = item.unitPriceNet, netValue = net, vatValue = vat,
                    grossValue = net + vat, vatRate = rate.code
                )
            }
        }

        // Korekta pełna do zera — negacja wierszy faktury pierwotnej
        if (original.source != RevenueSource.CRM) {
            throw ValidationException(
                "Korekta pełna jest dostępna tylko dla faktur wystawionych w CRM. " +
                    "Dla faktury zewnętrznej podaj jawnie pozycje korygujące."
            )
        }
        val originalItems = itemRepository.findByInvoiceIdOrderByLineNumberAsc(original.id)
        if (originalItems.isEmpty()) {
            throw ValidationException("Faktura pierwotna nie ma pozycji — podaj jawnie pozycje korygujące")
        }
        return originalItems.map { item ->
            CorrectionRow(
                name = item.name, unit = item.unit, quantity = item.quantity,
                unitPriceNet = -item.unitPriceNet,
                unitPriceGross = item.unitPriceGross?.let { -it },
                priceMode = item.priceMode,
                netValue = -item.netValue,
                vatValue = -item.vatValue, grossValue = -item.grossValue, vatRate = item.vatRate
            )
        }
    }

    /** VAT z zachowaniem znaku podstawy (korekty zmniejszające mają ujemne netto). */
    private fun signedVat(rate: VatRate, net: Long): Long =
        if (net >= 0) rate.vatFromNet(net) else -rate.vatFromNet(-net)
}
