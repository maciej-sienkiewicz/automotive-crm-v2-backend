package pl.detailing.crm.ksef.revenue.issue

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.ksef.domain.PaymentForm
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
import pl.detailing.crm.ksef.revenue.xml.Fa3XmlBuilder
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ── Commands ───────────────────────────────────────────────────────────────────

/**
 * Pozycja faktury — dokładnie jedno z pól [unitPriceNet] / [unitPriceGross] musi być
 * podane. Kwota wpisana jest źródłem prawdy: przy netto brutto = netto + VAT(netto),
 * przy brutto netto = brutto − VAT(brutto, wzór art. 106e ust. 7) — wpisana kwota
 * nigdy nie „przeskakuje" o grosz.
 */
data class RevenueInvoiceItemCommand(
    val name: String,
    val unit: String? = "szt.",
    val quantity: BigDecimal = BigDecimal.ONE,
    /** Cena jednostkowa netto w groszach (tryb NET). */
    val unitPriceNet: Long? = null,
    /** Cena jednostkowa brutto w groszach (tryb GROSS). */
    val unitPriceGross: Long? = null,
    /** Kod stawki FA(3): 23 | 8 | 5 | 0 | zw. */
    val vatRate: String
)

data class RevenueInvoiceBuyerCommand(
    /** NIP → faktura B2B; null/blank → konsument (B2C, BrakID=1). */
    val nip: String? = null,
    val name: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val countryCode: String = "PL",
    val email: String? = null
)

data class IssueRevenueInvoiceCommand(
    val studioId: StudioId,
    val userId: UserId,
    val buyer: RevenueInvoiceBuyerCommand,
    val items: List<RevenueInvoiceItemCommand>,
    val issueDate: LocalDate = LocalDate.now(),
    val saleDate: LocalDate? = null,
    /** PaymentForm.name (GOTOWKA / KARTA / PRZELEW / …). */
    val paymentForm: String? = null,
    val isPaid: Boolean = false,
    val paymentDueDate: LocalDate? = null,
    /** Podstawa prawna zwolnienia (P_19A) — wymagana, gdy występuje stawka zw. */
    val exemptionLegalBasis: String? = null,
    val visitId: UUID? = null,
    val customerId: UUID? = null,
    val description: String? = null
)

/** Pozycja faktury z policzonymi kwotami (grosze). */
private data class ComputedItem(
    val command: RevenueInvoiceItemCommand,
    val rate: VatRate,
    val priceMode: PriceMode,
    val unitPriceNet: Long,
    val unitPriceGross: Long?,
    val netValue: Long,
    val vatValue: Long,
    val grossValue: Long
)

/**
 * Wystawia fakturę przychodową: waliduje dane, nadaje numer (FV/{rok}/{seq}),
 * buduje XML FA(3) z danymi sprzedawcy pobranymi ze StudioSettings (snapshot)
 * i wysyła do KSeF. Faktura jest utrwalana przed wysyłką — błąd łączności nie
 * gubi dokumentu, tylko kieruje go do kolejki retry (tryb offline24).
 */
@Service
class IssueRevenueInvoiceHandler(
    private val invoiceRepository: KsefRevenueInvoiceRepository,
    private val itemRepository: KsefRevenueInvoiceItemRepository,
    private val settingsRepository: StudioSettingsRepository,
    private val xmlBuilder: Fa3XmlBuilder,
    private val dispatchService: KsefRevenueDispatchService,
    private val numberGenerator: RevenueInvoiceNumberGenerator
) {
    private val log = LoggerFactory.getLogger(IssueRevenueInvoiceHandler::class.java)

    companion object {
        const val INVOICE_PREFIX = "FV"
        private val NIP_PATTERN = Regex("^\\d{10}$")

        /** Ile razy ponawiamy zapis, gdy numer sprzątnął nam równoległy wątek. */
        const val NUMBER_COLLISION_ATTEMPTS = 5
    }

    fun handle(command: IssueRevenueInvoiceCommand): KsefRevenueInvoiceEntity {
        val invoice = persistWithNumberRetry(command)
        // Wysyłka poza transakcją zapisu — długotrwała operacja sieciowa nie może
        // trzymać transakcji DB, a faktura ma przetrwać niezależnie od wyniku wysyłki
        return dispatchService.dispatch(invoice)
    }

    /**
     * Numer jest nadawany jako MAX+1, więc dwa równoległe wystawienia dla tego samego
     * NIP-u mogą trafić na ten sam numer. Rozstrzyga to indeks unikalny w bazie —
     * przegrany wątek po prostu bierze kolejny numer. Lepsze to niż blokada na
     * poziomie tabeli: kolizja jest rzadka, a serializacja kosztowałaby każdą fakturę.
     */
    private fun persistWithNumberRetry(command: IssueRevenueInvoiceCommand): KsefRevenueInvoiceEntity {
        repeat(NUMBER_COLLISION_ATTEMPTS) { attempt ->
            try {
                return persistInvoice(command)
            } catch (e: DataIntegrityViolationException) {
                log.warn(
                    "Kolizja numeru faktury (próba {}/{}): {}",
                    attempt + 1, NUMBER_COLLISION_ATTEMPTS, e.mostSpecificCause.message
                )
            }
        }
        throw ValidationException(
            "Nie udało się nadać unikalnego numeru faktury — spróbuj ponownie za chwilę"
        )
    }

    @Transactional
    fun persistInvoice(command: IssueRevenueInvoiceCommand): KsefRevenueInvoiceEntity {
        validate(command)

        val settings = settingsRepository.findById(command.studioId.value).orElse(null)
        val sellerNip = settings?.taxId?.replace(Regex("[^0-9]"), "")
        val sellerName = settings?.name
        if (sellerNip.isNullOrBlank() || sellerName.isNullOrBlank()) {
            throw ValidationException(
                "Aby wystawiać faktury w KSeF, uzupełnij dane firmy (nazwa i NIP) w ustawieniach studia"
            )
        }

        val computed = computeItems(command.items)
        val totalNet = computed.sumOf { it.netValue }
        val totalVat = computed.sumOf { it.vatValue }
        val totalGross = computed.sumOf { it.grossValue }

        val invoiceNumber = numberGenerator.next(sellerNip, INVOICE_PREFIX, command.issueDate)
        val buyerNip = command.buyer.nip?.replace(Regex("[^0-9]"), "")?.takeIf { it.isNotBlank() }

        val invoice = KsefRevenueInvoiceEntity(
            studioId           = command.studioId.value,
            source             = RevenueSource.CRM,
            ksefStatus         = KsefRevenueStatus.PENDING,
            invoiceNumber      = invoiceNumber,
            invoiceType        = RevenueInvoiceType.VAT,
            issueDate          = command.issueDate,
            saleDate           = command.saleDate,
            sellerNip          = sellerNip,
            sellerName         = sellerName,
            sellerAddressLine1 = settings.street,
            sellerAddressLine2 = listOfNotNull(settings.postalCode, settings.city)
                .joinToString(" ").ifBlank { null },
            sellerBankAccount  = settings.bankAccount,
            buyerNip           = buyerNip,
            buyerName          = command.buyer.name?.trim()?.ifBlank { null },
            buyerAddressLine1  = command.buyer.addressLine1?.trim()?.ifBlank { null },
            buyerAddressLine2  = command.buyer.addressLine2?.trim()?.ifBlank { null },
            buyerCountryCode   = command.buyer.countryCode.ifBlank { "PL" },
            buyerEmail         = command.buyer.email?.trim()?.ifBlank { null },
            totalNet           = totalNet,
            totalVat           = totalVat,
            totalGross         = totalGross,
            paymentForm        = command.paymentForm,
            paymentStatus      = if (command.isPaid) "PAID" else "PENDING",
            paymentDueDate     = command.paymentDueDate,
            paidAt             = if (command.isPaid) Instant.now() else null,
            visitId            = command.visitId,
            customerId         = command.customerId,
            description        = command.description?.trim()?.ifBlank { null },
            createdBy          = command.userId.value
        )

        val items = computed.mapIndexed { index, item ->
            KsefRevenueInvoiceItemEntity(
                invoiceId      = invoice.id,
                lineNumber     = index + 1,
                name           = item.command.name.trim(),
                unit           = item.command.unit?.trim()?.ifBlank { null },
                quantity       = item.command.quantity,
                unitPriceNet   = item.unitPriceNet,
                unitPriceGross = item.unitPriceGross,
                priceMode      = item.priceMode,
                netValue       = item.netValue,
                vatValue       = item.vatValue,
                grossValue     = item.grossValue,
                vatRate        = item.rate.code
            )
        }

        invoice.invoiceXml = xmlBuilder.build(
            invoice = invoice,
            items = items,
            exemptionLegalBasis = command.exemptionLegalBasis
        )

        // saveAndFlush: kolizja numeru ma wybuchnąć tutaj, zanim dopiszemy pozycje
        // i zanim wrócimy do wołającego — inaczej retry dostałby wyjątek dopiero
        // przy commicie, poza zasięgiem obsługi
        invoiceRepository.saveAndFlush(invoice)
        itemRepository.saveAll(items)

        log.info(
            "Faktura przychodowa utworzona: studio={} number={} gross={} buyer={}",
            command.studioId, invoiceNumber, totalGross, buyerNip ?: "B2C"
        )
        return invoice
    }

    // ── Private ────────────────────────────────────────────────────────────────

    private fun validate(command: IssueRevenueInvoiceCommand) {
        if (command.items.isEmpty()) {
            throw ValidationException("Faktura musi mieć co najmniej jedną pozycję")
        }
        command.items.forEachIndexed { i, item ->
            if (item.name.isBlank()) throw ValidationException("Pozycja ${i + 1}: nazwa jest wymagana")
            if ((item.unitPriceNet == null) == (item.unitPriceGross == null)) {
                throw ValidationException("Pozycja ${i + 1}: podaj dokładnie jedną cenę — netto albo brutto")
            }
            val price = item.unitPriceNet ?: item.unitPriceGross!!
            if (price < 0) throw ValidationException("Pozycja ${i + 1}: cena nie może być ujemna")
            if (item.quantity <= BigDecimal.ZERO) throw ValidationException("Pozycja ${i + 1}: ilość musi być dodatnia")
            runCatching { VatRate.fromCode(item.vatRate) }
                .getOrElse { throw ValidationException("Pozycja ${i + 1}: ${it.message}") }
        }
        val nip = command.buyer.nip?.replace(Regex("[^0-9]"), "")
        if (!nip.isNullOrBlank() && !NIP_PATTERN.matches(nip)) {
            throw ValidationException("NIP nabywcy musi mieć 10 cyfr")
        }
        if (nip.isNullOrBlank() && command.buyer.name.isNullOrBlank()) {
            throw ValidationException("Faktura dla konsumenta (bez NIP) wymaga imienia i nazwiska nabywcy")
        }
        val hasExempt = command.items.any { it.vatRate.equals("zw", ignoreCase = true) }
        if (hasExempt && command.exemptionLegalBasis.isNullOrBlank()) {
            throw ValidationException("Stawka 'zw' wymaga podania podstawy prawnej zwolnienia")
        }
        command.paymentForm?.let { form ->
            runCatching { PaymentForm.valueOf(form.uppercase()) }.getOrElse {
                throw ValidationException(
                    "Nieznana forma płatności: $form. Dozwolone: ${PaymentForm.entries.joinToString { it.name }}"
                )
            }
        }
        if (!command.isPaid && command.paymentForm?.uppercase() == PaymentForm.PRZELEW.name &&
            command.paymentDueDate == null
        ) {
            throw ValidationException("Płatność przelewem wymaga terminu płatności")
        }
    }

    /**
     * Kwota wpisana przez użytkownika (netto lub brutto) jest źródłem prawdy —
     * druga kwota jest pochodną liczoną jednorazowo, bez przeliczania wstecz.
     */
    private fun computeItems(items: List<RevenueInvoiceItemCommand>): List<ComputedItem> =
        items.map { item ->
            val rate = VatRate.fromCode(item.vatRate)
            val lineOf = { unitPrice: Long ->
                BigDecimal(unitPrice).multiply(item.quantity).setScale(0, RoundingMode.HALF_UP).toLong()
            }
            if (item.unitPriceGross != null) {
                // Cena jednostkowa netto jest pochodną ceny brutto („w stu"), a wartości
                // wiersza to jej wielokrotności — dzięki temu P_11 == P_9A × P_8B
                // (reguła KSeF), a netto + VAT daje dokładnie wpisaną kwotę brutto.
                val unitNet = item.unitPriceGross - rate.vatFromGross(item.unitPriceGross)
                val grossValue = lineOf(item.unitPriceGross)
                val netValue = lineOf(unitNet)
                ComputedItem(
                    command = item, rate = rate, priceMode = PriceMode.GROSS,
                    unitPriceNet = unitNet,
                    unitPriceGross = item.unitPriceGross,
                    netValue = netValue, vatValue = grossValue - netValue, grossValue = grossValue
                )
            } else {
                val netValue = lineOf(item.unitPriceNet!!)
                val vatValue = rate.vatFromNet(netValue)
                ComputedItem(
                    command = item, rate = rate, priceMode = PriceMode.NET,
                    unitPriceNet = item.unitPriceNet,
                    unitPriceGross = null,
                    netValue = netValue, vatValue = vatValue, grossValue = netValue + vatValue
                )
            }
        }
}
