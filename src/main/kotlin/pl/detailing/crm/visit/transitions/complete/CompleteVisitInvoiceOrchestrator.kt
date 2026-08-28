package pl.detailing.crm.visit.transitions.complete

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.finance.document.CreateFinancialDocumentCommand
import pl.detailing.crm.finance.document.CreateFinancialDocumentHandler
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentSource
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.ksef.domain.PaymentForm
import pl.detailing.crm.ksef.revenue.domain.VatRate
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceEntity
import pl.detailing.crm.ksef.revenue.issue.IssueRevenueInvoiceCommand
import pl.detailing.crm.ksef.revenue.issue.IssueRevenueInvoiceHandler
import pl.detailing.crm.ksef.revenue.issue.RevenueInvoiceBuyerCommand
import pl.detailing.crm.ksef.revenue.issue.RevenueInvoiceItemCommand
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.CapabilityService
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

// ── Commands ───────────────────────────────────────────────────────────────────

/**
 * Pozycja faktury — dokładnie jedno z pól [unitPriceNet]/[unitPriceGross].
 * Kwota wpisana przez użytkownika jest źródłem prawdy (nie jest przeliczana wstecz).
 */
data class CompleteInvoiceItem(
    val name: String,
    val quantity: BigDecimal = BigDecimal.ONE,
    /** Cena jednostkowa netto w groszach (tryb NET). */
    val unitPriceNet: Long? = null,
    /** Cena jednostkowa brutto w groszach (tryb GROSS — VAT wzorem art. 106e ust. 7). */
    val unitPriceGross: Long? = null,
    /** Kod stawki FA(3): 23 | 8 | 5 | 0 | zw. */
    val vatRate: String = "23"
)

data class CompleteInvoiceBuyer(
    val nip: String? = null,
    val name: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val email: String? = null
)

/**
 * Szczegóły faktury przy zakończeniu wizyty — pozycje mogły zostać zmienione
 * przez użytkownika (nazwy, kwoty) względem usług wizyty.
 */
data class CompleteInvoiceDetails(
    val items: List<CompleteInvoiceItem>,
    val buyer: CompleteInvoiceBuyer = CompleteInvoiceBuyer(),
    /**
     * Metoda płatności dla dokumentu pokrywającego resztę kwoty wizyty.
     * Wymagana, gdy suma pozycji faktury jest mniejsza niż kwota wizyty.
     */
    val remainderPaymentMethod: PaymentMethod? = null,
    val exemptionLegalBasis: String? = null,
    /**
     * Czy fakturę od razu wysłać do KSeF. null = użyj domyślnej odpowiedzi studia
     * (StudioSettings.ksefAutoSendDefault) — klient, który nie zna przełącznika,
     * dostaje zachowanie ustawione w ustawieniach, a nie zaszyte w kodzie.
     */
    val sendToKsef: Boolean? = null
)

data class CompleteVisitWithInvoiceResult(
    val completion: CompleteVisitResult,
    val ksefInvoice: KsefRevenueInvoiceEntity,
    val remainderDocumentId: java.util.UUID?,
    val remainderDocumentNumber: String?
)

/**
 * Zakończenie wizyty z wystawieniem faktury KSeF, z możliwością podziału kwoty
 * na fakturę i drugi dokument przychodowy (paragon).
 *
 * Niezmiennik księgowy: **suma kwot faktury i dokumentu reszty musi być równa
 * kwocie wizyty** — całość przychodu z wizyty jest zawsze udokumentowana.
 * Nie istnieje ścieżka, w której część kwoty pozostaje bez dokumentu.
 *
 * Kolejność wykonania (świadoma):
 * 1. Pre-walidacja wszystkiego, co mogłoby się nie powieść (dane firmy, kwoty,
 *    pozycje) — ZANIM wizyta zmieni status,
 * 2. zakończenie wizyty + zapis dokumentu FinancialDocument INVOICE na kwotę
 *    fakturowaną (adnotacja w module finansów, jak dotychczas),
 * 3. dokument FinancialDocument RECEIPT na resztę (gotówka → wpis do kasy),
 * 4. wystawienie i wysyłka faktury do KSeF — operacja sieciowa na końcu, poza
 *    transakcjami; błąd łączności nie cofa zakończenia wizyty, bo faktura
 *    trafia do kolejki offline24 i jest dosyłana automatycznie.
 */
@Service
class CompleteVisitInvoiceOrchestrator(
    private val completeVisitHandler: CompleteVisitHandler,
    private val issueInvoiceHandler: IssueRevenueInvoiceHandler,
    private val createFinancialDocumentHandler: CreateFinancialDocumentHandler,
    private val financialDocumentRepository: FinancialDocumentRepository,
    private val settingsRepository: StudioSettingsRepository,
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val capabilityService: CapabilityService,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(CompleteVisitInvoiceOrchestrator::class.java)

    suspend fun handle(
        command: CompleteVisitCommand,
        invoice: CompleteInvoiceDetails
    ): CompleteVisitWithInvoiceResult {
        // Point-of-effect (W1): issuing a KSeF invoice is the finance module.
        // Checked before any state changes — the caller receives 402 with upsell.
        capabilityService.requireCapability(command.studioId, CapabilityKey.FINANCE_INVOICE_ISSUE)

        // ── 1. Pre-walidacja (nic jeszcze nie zmieniamy) ───────────────────────
        if (invoice.items.isEmpty()) {
            throw ValidationException("Faktura musi mieć co najmniej jedną pozycję")
        }
        invoice.items.forEachIndexed { i, item ->
            if (item.name.isBlank()) throw ValidationException("Pozycja ${i + 1}: nazwa jest wymagana")
            if ((item.unitPriceNet == null) == (item.unitPriceGross == null)) {
                throw ValidationException("Pozycja ${i + 1}: podaj dokładnie jedną cenę — netto albo brutto")
            }
            if ((item.unitPriceNet ?: item.unitPriceGross!!) <= 0) {
                throw ValidationException("Pozycja ${i + 1}: kwota musi być dodatnia")
            }
            runCatching { VatRate.fromCode(item.vatRate) }
                .getOrElse { throw ValidationException("Pozycja ${i + 1}: ${it.message}") }
        }

        val settings = settingsRepository.findById(command.studioId.value).orElse(null)
        if (settings?.taxId.isNullOrBlank() || settings?.name.isNullOrBlank()) {
            throw ValidationException(
                "Brak danych firmy (nazwa i NIP) wymaganych do wystawienia faktury w KSeF. " +
                    "Uzupełnij dane firmy w ustawieniach studia i spróbuj ponownie."
            )
        }

        val visitEntity = visitRepository.findByIdAndStudioIdWithPhotos(command.visitId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Visit with ID '${command.visitId}' not found")
        val visit = visitEntity.toDomain()

        val invoiceTotals = computeInvoiceTotals(invoice.items)
        val visitGross = visit.calculateTotalGross().amountInCents
        val remainderGross = visitGross - invoiceTotals.gross

        if (remainderGross < 0) {
            throw ValidationException(
                "Kwota faktury (${grosz(invoiceTotals.gross)} zł) przekracza kwotę wizyty (${grosz(visitGross)} zł)"
            )
        }
        if (remainderGross > 0 && invoice.remainderPaymentMethod == null) {
            throw ValidationException(
                "Kwota faktury (${grosz(invoiceTotals.gross)} zł) jest niższa niż kwota wizyty " +
                    "(${grosz(visitGross)} zł). Reszta (${grosz(remainderGross)} zł) musi zostać " +
                    "udokumentowana drugim dokumentem — wskaż metodę płatności reszty."
            )
        }
        if (command.paymentMethod == PaymentMethod.TRANSFER && command.dueDate == null) {
            throw ValidationException("Płatność przelewem wymaga terminu płatności")
        }

        val customer = customerRepository.findByIdAndStudioId(visit.customerId.value, command.studioId.value)

        // ── 2. Zakończenie wizyty + FinancialDocument INVOICE (kwota fakturowana) ─
        val completion = completeVisitHandler.handle(
            command.copy(
                documentType = DocumentType.INVOICE,
                documentTotalsOverride = DocumentTotals(
                    net = invoiceTotals.net,
                    vat = invoiceTotals.vat,
                    gross = invoiceTotals.gross
                )
            )
        )

        // ── 3. Dokument reszty (paragon) — kasa aktualizowana przy gotówce ─────
        var remainderId: java.util.UUID? = null
        var remainderNumber: String? = null
        if (remainderGross > 0) {
            val remainderNet = BigDecimal(remainderGross)
                .multiply(BigDecimal(100))
                .divide(BigDecimal(123), 0, RoundingMode.HALF_UP)
                .toLong()
            val remainderDoc = createFinancialDocumentHandler.handle(
                CreateFinancialDocumentCommand(
                    studioId          = command.studioId,
                    userId            = command.userId,
                    userDisplayName   = command.userName ?: "",
                    source            = DocumentSource.VISIT,
                    visitId           = visit.id,
                    vehicleBrand      = visit.brandSnapshot,
                    vehicleModel      = visit.modelSnapshot,
                    customerFirstName = customer?.firstName,
                    customerLastName  = customer?.lastName,
                    documentType      = DocumentType.RECEIPT,
                    direction         = DocumentDirection.INCOME,
                    paymentMethod     = invoice.remainderPaymentMethod!!,
                    totalNet          = remainderNet,
                    totalVat          = remainderGross - remainderNet,
                    totalGross        = remainderGross,
                    issueDate         = LocalDate.now(),
                    dueDate           = LocalDate.now(),
                    description       = "Wizyta #${visit.visitNumber} — reszta kwoty poza fakturą",
                    counterpartyName  = listOfNotNull(customer?.firstName, customer?.lastName)
                        .joinToString(" ").ifBlank { null },
                    counterpartyNip   = null
                )
            )
            remainderId = remainderDoc.id.value
            remainderNumber = remainderDoc.documentNumber
            log.info(
                "Reszta kwoty wizyty {} udokumentowana paragonem {} ({} gr, {})",
                visit.visitNumber, remainderNumber, remainderGross, invoice.remainderPaymentMethod
            )
        }

        // ── 4. Faktura KSeF (sieć na końcu; błąd łączności → offline24) ────────
        val buyerNip = invoice.buyer.nip?.replace(Regex("[^0-9]"), "")?.ifBlank { null }
            ?: customer?.companyNip?.replace(Regex("[^0-9]"), "")?.ifBlank { null }
        val buyerName = invoice.buyer.name?.trim()?.ifBlank { null }
            ?: customer?.companyName?.takeIf { it.isNotBlank() && buyerNip != null }
            ?: listOfNotNull(customer?.firstName, customer?.lastName).joinToString(" ").ifBlank { null }

        val ksefInvoice = issueInvoiceHandler.handle(
            IssueRevenueInvoiceCommand(
                studioId = command.studioId,
                userId = command.userId,
                buyer = RevenueInvoiceBuyerCommand(
                    nip          = buyerNip,
                    name         = buyerName,
                    addressLine1 = invoice.buyer.addressLine1,
                    addressLine2 = invoice.buyer.addressLine2,
                    email        = invoice.buyer.email ?: customer?.email
                ),
                items = invoice.items.map {
                    RevenueInvoiceItemCommand(
                        name           = it.name.trim(),
                        unit           = "szt.",
                        quantity       = it.quantity,
                        unitPriceNet   = it.unitPriceNet,
                        unitPriceGross = it.unitPriceGross,
                        vatRate        = it.vatRate
                    )
                },
                saleDate            = LocalDate.now(),
                paymentForm         = toPaymentForm(command.paymentMethod)?.name,
                isPaid              = command.paymentMethod != PaymentMethod.TRANSFER,
                paymentDueDate      = command.dueDate,
                exemptionLegalBasis = invoice.exemptionLegalBasis,
                visitId             = visit.id.value,
                customerId          = visit.customerId.value,
                description         = "Wizyta #${visit.visitNumber}",
                sendToKsef          = invoice.sendToKsef ?: (settings?.ksefAutoSendDefault ?: true)
            )
        )

        // ── 5. Dane nabywcy wracają do kartoteki klienta ───────────────────────
        // Kto uzupełnia NIP i nazwę firmy na fakturze, robi to raz — następne
        // wydanie ma je zastać w prefillu, niezależnie od tego, czy KSeF jest
        // skonfigurowany. Zapis po wystawieniu faktury: kosmetyka kartoteki nie
        // ma prawa wywrócić zakończonej wizyty ani faktury.
        runCatching { persistBuyerToCustomer(command, customer, invoice.buyer, buyerNip) }
            .onFailure {
                log.warn(
                    "Nie udało się zapisać danych nabywcy do kartoteki klienta {}: {}",
                    visit.customerId, it.message
                )
            }

        // Powiązanie dokumentu finansowego z fakturą KSeF — zunifikowana lista
        // dokumentów przychodowych prezentuje wtedy jeden rekord zamiast dwóch
        completion.financialDocumentId?.let { documentId ->
            financialDocumentRepository.findById(documentId.value).ifPresent { document ->
                document.ksefRevenueInvoiceId = ksefInvoice.id
                financialDocumentRepository.save(document)
            }
        }

        return CompleteVisitWithInvoiceResult(
            completion = completion,
            ksefInvoice = ksefInvoice,
            remainderDocumentId = remainderId,
            remainderDocumentNumber = remainderNumber
        )
    }

    // ── Private ────────────────────────────────────────────────────────────────

    /**
     * Nadpisuje dane firmowe klienta danymi nabywcy z faktury.
     *
     * Tylko dla faktury FIRMOWEJ (jest NIP): nabywca bez NIP-u to konsument, a jego
     * „nazwa" bywa po prostu imieniem i nazwiskiem z kartoteki — wpisanie jej w pole
     * companyName robiłoby z osoby firmę. Zasada nadpisywania: pole formularza
     * wygrywa, gdy niesie wartość; puste pole NICZEGO nie kasuje — „zapisz, żeby
     * odtworzyć następnym razem", nie „wyczyść, bo tym razem nie wpisano".
     * E-mail kontaktowy tylko uzupełniamy, gdy kartoteka nie ma żadnego:
     * e-mail z faktury to często ksiegowosc@, a kontaktowym chodzą SMS-y
     * i powiadomienia wizyt.
     */
    private fun persistBuyerToCustomer(
        command: CompleteVisitCommand,
        customer: CustomerEntity?,
        buyer: CompleteInvoiceBuyer,
        buyerNip: String?
    ) {
        if (customer == null || buyerNip == null) return

        val newName = buyer.name?.trim()?.ifBlank { null }
        // Firma bez nazwy nie istnieje w modelu klienta: CustomerEntity.toDomain()
        // buduje companyData tylko przy niepustym companyName, więc zapis samego
        // NIP-u byłby niewidoczny dla odczytów (w tym dla prefillu nabywcy).
        val effectiveName = newName ?: customer.companyName?.takeIf { it.isNotBlank() } ?: return
        val newStreet = buyer.addressLine1?.trim()?.ifBlank { null }
        val (newPostal, newCity) = parseAddressLine2(buyer.addressLine2)
        val newEmail = buyer.email?.trim()?.ifBlank { null }

        val oldValues = mapOf(
            "companyNip" to customer.companyNip,
            "companyName" to customer.companyName,
            "companyAddressStreet" to customer.companyAddressStreet,
            "companyAddressPostalCode" to customer.companyAddressPostalCode,
            "companyAddressCity" to customer.companyAddressCity,
            "email" to customer.email
        )

        customer.companyNip = buyerNip
        customer.companyName = effectiveName
        newStreet?.let { customer.companyAddressStreet = it }
        newPostal?.let { customer.companyAddressPostalCode = it }
        newCity?.let { customer.companyAddressCity = it }
        if (customer.email.isNullOrBlank() && newEmail != null) customer.email = newEmail
        // Kompletny adres wymaga kraju (encja składa CompanyAddress tylko z pełnych
        // czterech pól) — domyślnie Polska; istniejącej wartości nie ruszamy.
        if (customer.companyAddressCountry.isNullOrBlank() && customer.companyAddressStreet != null) {
            customer.companyAddressCountry = "Polska"
        }

        val newValues = mapOf(
            "companyNip" to customer.companyNip,
            "companyName" to customer.companyName,
            "companyAddressStreet" to customer.companyAddressStreet,
            "companyAddressPostalCode" to customer.companyAddressPostalCode,
            "companyAddressCity" to customer.companyAddressCity,
            "email" to customer.email
        )
        val changes = auditService.computeChanges(oldValues, newValues)
        if (changes.isEmpty()) return

        customer.updatedBy = command.userId.value
        customer.updatedAt = java.time.Instant.now()
        customerRepository.save(customer)

        auditService.logSync(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.CUSTOMER,
            entityId = customer.id.toString(),
            entityDisplayName = listOfNotNull(customer.firstName, customer.lastName)
                .joinToString(" ").ifBlank { customer.companyName },
            action = AuditAction.UPDATE,
            changes = changes,
            metadata = mapOf("source" to "INVOICE_BUYER")
        ))
        log.info("Dane nabywcy z faktury zapisane w kartotece klienta {}", customer.id)
    }

    /**
     * Druga linia adresu faktury powstaje we froncie jako „kod-pocztowy miasto"
     * — odwracamy dokładnie ten format. Linia w innym kształcie nie nadpisuje
     * niczego: lepiej zostawić kartotekę, niż wpisać kod do pola miasta.
     */
    private fun parseAddressLine2(line2: String?): Pair<String?, String?> {
        val trimmed = line2?.trim()?.ifBlank { null } ?: return null to null
        val match = Regex("^(\\d{2}-\\d{3})\\s+(.+)$").find(trimmed) ?: return null to null
        return match.groupValues[1] to match.groupValues[2].trim()
    }

    internal data class InvoiceTotals(val net: Long, val vat: Long, val gross: Long)

    /**
     * Sumy liczone identycznie jak w module przychodowym KSeF: kwota wpisana
     * (netto lub brutto) jest źródłem prawdy — brutto wpisane pozostaje dokładne
     * (VAT „w stu"), netto wpisane pozostaje dokładne (VAT od netto).
     */
    internal fun computeInvoiceTotals(items: List<CompleteInvoiceItem>): InvoiceTotals {
        var net = 0L
        var vat = 0L
        var gross = 0L
        items.forEach { item ->
            val rate = VatRate.fromCode(item.vatRate)
            val lineOf = { unitPrice: Long ->
                BigDecimal(unitPrice).multiply(item.quantity).setScale(0, RoundingMode.HALF_UP).toLong()
            }
            if (item.unitPriceGross != null) {
                // Spójnie z IssueRevenueInvoiceHandler: netto to wielokrotność
                // jednostkowego netto wyliczonego „w stu" (P_11 == P_9A × P_8B)
                val unitNet = item.unitPriceGross - rate.vatFromGross(item.unitPriceGross)
                val lineGross = lineOf(item.unitPriceGross)
                val lineNet = lineOf(unitNet)
                net += lineNet
                vat += lineGross - lineNet
                gross += lineGross
            } else {
                val lineNet = lineOf(item.unitPriceNet!!)
                val lineVat = rate.vatFromNet(lineNet)
                net += lineNet
                vat += lineVat
                gross += lineNet + lineVat
            }
        }
        return InvoiceTotals(net, vat, gross)
    }

    /** Mapowanie metody płatności wizyty na formę płatności FA(3). */
    internal fun toPaymentForm(method: PaymentMethod): PaymentForm? = when (method) {
        PaymentMethod.CASH          -> PaymentForm.GOTOWKA
        PaymentMethod.CARD          -> PaymentForm.KARTA
        PaymentMethod.TRANSFER      -> PaymentForm.PRZELEW
        PaymentMethod.BLIK_NA_NUMER,
        PaymentMethod.BLIK_TERMINAL -> PaymentForm.MOBILNA
        PaymentMethod.OTHER         -> null
    }

    private fun grosz(amount: Long): String =
        BigDecimal(amount).movePointLeft(2).setScale(2).toPlainString()
}
