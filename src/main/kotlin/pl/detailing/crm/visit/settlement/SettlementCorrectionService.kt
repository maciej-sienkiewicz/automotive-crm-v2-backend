package pl.detailing.crm.visit.settlement

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditAmount
import pl.detailing.crm.audit.domain.AuditContext
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.AuditValueType
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.audit.domain.auditMoney
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.finance.document.CreateFinancialDocumentCommand
import pl.detailing.crm.finance.document.CreateFinancialDocumentHandler
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentSource
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus
import pl.detailing.crm.ksef.revenue.domain.RevenueInvoiceType
import pl.detailing.crm.ksef.revenue.domain.RevenueSource
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceEntity
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import pl.detailing.crm.ksef.revenue.issue.IssueCorrectionCommand
import pl.detailing.crm.ksef.revenue.issue.IssueCorrectionHandler
import pl.detailing.crm.ksef.revenue.issue.IssueRevenueInvoiceCommand
import pl.detailing.crm.ksef.revenue.issue.IssueRevenueInvoiceHandler
import pl.detailing.crm.ksef.revenue.issue.RevenueInvoiceBuyerCommand
import pl.detailing.crm.ksef.revenue.issue.RevenueInvoiceItemCommand
import pl.detailing.crm.push.notify.PushMessages
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VatRate
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.CapabilityService
import pl.detailing.crm.visit.domain.Visit
import pl.detailing.crm.visit.domain.VisitServiceItem
import pl.detailing.crm.visit.domain.auditDisplayName
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.transitions.complete.CompleteInvoiceItem
import pl.detailing.crm.visit.transitions.complete.CompleteVisitInvoiceOrchestrator
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Wynik wykonanej poprawki. */
data class SettlementCorrectionResult(
    val correctionId: UUID,
    val steps: List<String>,
    /** Krok KSeF, który się nie udał po zapisie poprawki — do dokończenia w module KSeF. */
    val ksefError: String?
)

/**
 * Poprawka rozliczenia wizyty po wydaniu pojazdu.
 *
 * Reguła nadrzędna: nic nie jest usuwane ani nadpisywane. Dokument, który przestaje
 * obowiązywać, zostaje w historii jako „zastąpiony", a obok niego staje korekta storno
 * z kwotami ze znakiem — każda suma po dokumentach (raport form płatności, kasa, kafle)
 * wychodzi poprawnie bez warunków na typ. Faktura KSeF:
 *   • niewysłana (nie weszła do sesji) — anulowana, numer zostaje zajęty,
 *   • w drodze (wysyłana, w sesji) — poprawka czeka, aż KSeF odpowie,
 *   • przyjęta — korekta do zera i nowa faktura (decyzja biznesu: dwa czytelne
 *     dokumenty zamiast jednej korekty różnicowej),
 *   • paragon → faktura bez zmiany kwot — faktura do paragonu (FP), paragon zostaje.
 * Zmiana samej formy płatności nie rusza faktury: zmieniają się dokumenty w CRM i kasa.
 *
 * Pieniądze w bazie zmieniają się w jednej transakcji z blokadą wiersza wizyty (ceny,
 * storna, nowe dokumenty, kasa, anulowanie faktury). Operacje sieciowe KSeF — korekta
 * i nowa faktura — idą po commicie, bo numeracja faktur ma własne ponawianie kolizji,
 * którego nie wolno zamknąć w cudzej transakcji. Ich błąd zostaje w historii poprawki.
 */
@Service
class SettlementCorrectionService(
    private val visitRepository: VisitRepository,
    private val documentRepository: FinancialDocumentRepository,
    private val invoiceRepository: KsefRevenueInvoiceRepository,
    private val correctionRepository: VisitSettlementCorrectionRepository,
    private val createDocumentHandler: CreateFinancialDocumentHandler,
    private val issueInvoiceHandler: IssueRevenueInvoiceHandler,
    private val issueCorrectionHandler: IssueCorrectionHandler,
    private val invoiceOrchestrator: CompleteVisitInvoiceOrchestrator,
    private val customerRepository: CustomerRepository,
    private val settingsRepository: StudioSettingsRepository,
    private val capabilityService: CapabilityService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate
) {
    private val log = LoggerFactory.getLogger(SettlementCorrectionService::class.java)

    // ── Odczyt ─────────────────────────────────────────────────────────────────

    internal fun loadVisit(studioId: pl.detailing.crm.shared.StudioId, visitId: pl.detailing.crm.shared.VisitId): Visit =
        visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value)
            ?.toDomain()
            ?: throw EntityNotFoundException("Wizyta nie została znaleziona")

    internal fun loadState(studioId: UUID, visitId: UUID): SettlementState {
        val documents = documentRepository.findAllByVisitIdAndStudioIdAndDeletedAtIsNull(visitId, studioId)
            .filter { it.supersededAt == null && it.documentType != DocumentType.CORRECTION && it.direction == DocumentDirection.INCOME }
            .sortedBy { it.createdAt }
        val invoices = invoiceRepository.findByStudioIdAndVisitIdOrderByCreatedAtAsc(studioId, visitId)
        val corrections = invoices.filter { it.invoiceType == RevenueInvoiceType.KOR && it.ksefStatus !in DEAD }
        val active = invoices.filter { invoice ->
            invoice.invoiceType == RevenueInvoiceType.VAT &&
                invoice.source == RevenueSource.CRM &&
                invoice.ksefStatus !in DEAD &&
                // Faktura wyzerowana korektą przestała obowiązywać — zostaje tylko w historii.
                invoice.totalGross + corrections.filter { it.originalInvoiceId == invoice.id }.sumOf { it.totalGross } != 0L
        }
        val main = documents.firstOrNull { it.documentType == DocumentType.INVOICE } ?: documents.firstOrNull()
        return SettlementState(
            activeDocuments = documents,
            activeInvoices = active,
            documentType = main?.documentType,
            paymentMethod = main?.paymentMethod
        )
    }

    // ── Planowanie ─────────────────────────────────────────────────────────────

    fun preview(command: SettlementCorrectionCommand): SettlementPreview {
        val visit = loadVisit(command.studioId, command.visitId)
        val plan = plan(visit, loadState(command.studioId.value, visit.id.value), command)
        return SettlementPreview(
            steps = plan.steps,
            blockReason = plan.blockReason,
            totalGrossBefore = plan.totalGrossBefore,
            totalGrossAfter = plan.totalGrossAfter,
            customerDifference = plan.customerDifference,
            cashDelta = plan.cashDelta
        )
    }

    internal fun plan(visit: Visit, state: SettlementState, command: SettlementCorrectionCommand): SettlementPlan {
        val block = { reason: String -> emptyPlan(visit, reason) }
        if (visit.status != VisitStatus.COMPLETED) return block("Rozliczenie można poprawić tylko w wizycie wydanej klientowi.")
        if (command.documentType == DocumentType.CORRECTION) return block("Nieprawidłowy rodzaj dokumentu.")

        val after = try {
            visit.correctSettledPrices(command.prices, command.userId)
        } catch (e: ValidationException) {
            return block(e.message ?: "Nieprawidłowa cena pozycji.")
        }
        val itemsChanged = visit.serviceItems.zip(after.serviceItems).any { (a, b) -> a.priceDiffers(b) }

        val netBefore = visit.calculateTotalNet().amountInCents
        val grossBefore = visit.calculateTotalGross().amountInCents
        val netAfter = after.calculateTotalNet().amountInCents
        val grossAfter = after.calculateTotalGross().amountInCents

        val hasFinance = capabilityService.hasCapability(command.studioId, CapabilityKey.FINANCE_INVOICE_ISSUE)
        val currentType = state.documentType
        val typeChanged = currentType != null && command.documentType != currentType
        val methodChanged = state.paymentMethod != null && command.paymentMethod != state.paymentMethod
        val linkedInvoiceIds = state.invoiceDocuments.mapNotNull { it.ksefRevenueInvoiceId }.toSet()
        val receiptInvoices = state.activeInvoices.filter { it.id !in linkedInvoiceIds }
        val buyerChanged = command.documentType == DocumentType.INVOICE &&
            state.activeInvoices.lastOrNull()?.let { !sameBuyer(it, command.buyer) } == true

        if (command.paymentMethod == PaymentMethod.TRANSFER && command.dueDate == null) {
            return block("Płatność przelewem wymaga terminu płatności.")
        }
        if (command.documentType == DocumentType.INVOICE) {
            if (!hasFinance) return block("Wystawianie faktur wymaga modułu Finanse.")
            val settings = settingsRepository.findById(command.studioId.value).orElse(null)
            if (settings?.taxId.isNullOrBlank() || settings?.name.isNullOrBlank()) {
                return block("Uzupełnij dane firmy (nazwa i NIP) w ustawieniach studia, zanim wystawisz fakturę.")
            }
        }

        // ── Co się dzieje z dokumentami ──────────────────────────────────────
        var documentsToReplace: List<FinancialDocumentEntity> = emptyList()
        var invoicesToTreat: List<KsefRevenueInvoiceEntity> = emptyList()
        var newDocumentType: DocumentType? = null
        var issueInvoice = false
        var invoiceToReceipt = false
        var cloneDocuments = false

        val nothingButMethod = !itemsChanged && !typeChanged && !buyerChanged
        // Wizyta wydana bez dokumentu (np. zanim studio miało moduł Finanse): sam wybór
        // rodzaju dokumentu to zmiana — dopisuje brakujący paragon albo fakturę.
        val missingDocument = currentType == null && state.activeInvoices.isEmpty() && hasFinance && grossAfter > 0
        when {
            // Tylko forma płatności: faktury zostają, dokumenty wracają z nową formą.
            nothingButMethod && methodChanged -> {
                documentsToReplace = state.activeDocuments
                cloneDocuments = true
            }
            // Paragon → faktura bez zmiany kwot: faktura do paragonu, paragon zostaje.
            currentType != null && currentType != DocumentType.INVOICE &&
                command.documentType == DocumentType.INVOICE && !itemsChanged -> {
                if (receiptInvoices.isNotEmpty() && !buyerChanged && !methodChanged) {
                    return block("Do tego paragonu jest już faktura z tymi samymi danymi.")
                }
                invoicesToTreat = receiptInvoices
                issueInvoice = true
                invoiceToReceipt = true
                if (methodChanged) {
                    documentsToReplace = state.activeDocuments
                    cloneDocuments = true
                }
            }
            nothingButMethod && !missingDocument -> return block("Nic się nie zmienia — popraw cenę, stawkę VAT, formę płatności albo rodzaj dokumentu.")
            else -> {
                documentsToReplace = state.activeDocuments
                invoicesToTreat = state.activeInvoices
                newDocumentType = command.documentType.takeIf { hasFinance && grossAfter > 0 }
                issueInvoice = newDocumentType == DocumentType.INVOICE
            }
        }

        // ── Faktury KSeF: co wolno zrobić w ich obecnym stanie ───────────────
        val treatments = mutableMapOf<UUID, InvoiceTreatment>()
        for (invoice in invoicesToTreat) {
            treatments[invoice.id] = when (invoice.ksefStatus) {
                in KsefRevenueStatus.CANCELLABLE -> InvoiceTreatment.CANCEL
                in KsefRevenueStatus.IN_FLIGHT -> return block(
                    "Faktura ${invoice.invoiceNumber} czeka na odpowiedź KSeF. Poczekaj, aż KSeF ją przyjmie, i popraw rozliczenie wtedy."
                )
                KsefRevenueStatus.ACCEPTED -> {
                    val hasCorrection = invoiceRepository.existsByStudioIdAndOriginalInvoiceIdAndKsefStatusNot(
                        command.studioId.value, invoice.id, KsefRevenueStatus.REJECTED
                    )
                    if (hasCorrection || invoice.ksefNumber == null) return block(
                        "Faktura ${invoice.invoiceNumber} ma już korektę w KSeF. Kolejną korektę wystaw w module KSeF."
                    )
                    InvoiceTreatment.CORRECT_TO_ZERO
                }
                else -> return block("Faktura ${invoice.invoiceNumber} jest w stanie, którego nie da się poprawić.")
            }
        }
        state.activeInvoices.filter { it.id !in treatments }.forEach { treatments[it.id] = InvoiceTreatment.KEEP }

        if (issueInvoice) {
            val buyer = effectiveBuyer(command, state, visit)
            if (buyer.normalizedNip == null && buyer.name.isNullOrBlank()) {
                return block("Faktura wymaga nabywcy: podaj NIP firmy albo imię i nazwisko klienta.")
            }
            try {
                issueInvoiceHandler.validate(invoiceCommand(command, after, buyer, invoiceToReceipt, sendToKsef = true))
            } catch (e: ValidationException) {
                return block(e.message ?: "Faktura nie przejdzie walidacji.")
            }
        }

        val cancels = treatments.values.count { it == InvoiceTreatment.CANCEL }
        val corrects = treatments.values.count { it == InvoiceTreatment.CORRECT_TO_ZERO }
        val ksefAction = when {
            invoiceToReceipt -> SettlementKsefAction.INVOICE_TO_RECEIPT
            corrects > 0 && issueInvoice -> SettlementKsefAction.CORRECT_AND_REISSUE
            corrects > 0 -> SettlementKsefAction.CORRECT
            cancels > 0 && issueInvoice -> SettlementKsefAction.CANCEL_AND_REISSUE
            cancels > 0 -> SettlementKsefAction.CANCEL
            issueInvoice -> SettlementKsefAction.ISSUE
            state.activeInvoices.isNotEmpty() -> SettlementKsefAction.KEEP
            else -> SettlementKsefAction.NONE
        }

        // ── Pieniądze: kasa i różnica dla klienta ────────────────────────────
        val cashOut = documentsToReplace
            .filter { it.paymentMethod == PaymentMethod.CASH && it.status == DocumentStatus.PAID }
            .sumOf { it.totalGross }
        val cashIn = when {
            command.paymentMethod != PaymentMethod.CASH -> 0L
            cloneDocuments -> documentsToReplace.sumOf { it.totalGross }
            newDocumentType != null -> grossAfter
            else -> 0L
        }

        val plan = SettlementPlan(
            itemsChanged = itemsChanged,
            documentsToReplace = documentsToReplace,
            invoiceTreatments = treatments,
            newDocumentType = newDocumentType,
            issueInvoice = issueInvoice,
            invoiceToReceipt = invoiceToReceipt,
            cloneDocuments = cloneDocuments,
            ksefAction = ksefAction,
            totalNetBefore = netBefore,
            totalGrossBefore = grossBefore,
            totalNetAfter = netAfter,
            totalGrossAfter = grossAfter,
            customerDifference = grossAfter - grossBefore,
            cashDelta = cashIn - cashOut,
            steps = emptyList(),
            blockReason = null
        )
        return plan.copy(steps = describe(plan, state, command))
    }

    private fun emptyPlan(visit: Visit, reason: String): SettlementPlan {
        val net = visit.calculateTotalNet().amountInCents
        val gross = visit.calculateTotalGross().amountInCents
        return SettlementPlan(
            itemsChanged = false, documentsToReplace = emptyList(), invoiceTreatments = emptyMap(),
            newDocumentType = null, issueInvoice = false, invoiceToReceipt = false, cloneDocuments = false,
            ksefAction = SettlementKsefAction.NONE, totalNetBefore = net, totalGrossBefore = gross,
            totalNetAfter = net, totalGrossAfter = gross, customerDifference = 0, cashDelta = 0,
            steps = emptyList(), blockReason = reason
        )
    }

    /** Zdania podglądu — każde mówi, co się stanie; bez skrótów i bez „·" (CLAUDE.md §4). */
    private fun describe(plan: SettlementPlan, state: SettlementState, command: SettlementCorrectionCommand): List<String> {
        val m = PushMessages::formatMoney
        val steps = mutableListOf<String>()
        if (plan.itemsChanged) {
            steps += "Kwota wizyty: ${m(plan.totalGrossBefore)} → ${m(plan.totalGrossAfter)} brutto. " +
                "Statystyki pokażą nową kwotę w miesiącu wizyty."
        }
        plan.documentsToReplace.forEach {
            steps += "Dokument ${it.documentNumber} (${m(it.totalGross)}, ${it.paymentMethod.displayName.lowercase()}) " +
                "dostaje korektę i zostaje w historii."
        }
        if (plan.cloneDocuments) {
            steps += "Nowe dokumenty z tymi samymi kwotami, płatność: ${command.paymentMethod.displayName.lowercase()}."
        }
        plan.newDocumentType?.let {
            steps += "Nowy dokument (${it.displayName.lowercase()}) na ${m(plan.totalGrossAfter)}, " +
                "płatność: ${command.paymentMethod.displayName.lowercase()}."
        }
        state.activeInvoices.forEach { invoice ->
            when (plan.invoiceTreatments[invoice.id]) {
                InvoiceTreatment.CANCEL -> steps += "Faktura ${invoice.invoiceNumber} nie trafiła do KSeF, więc zostanie anulowana. " +
                    "Jej numer zostaje zajęty."
                InvoiceTreatment.CORRECT_TO_ZERO -> steps += "Faktura ${invoice.invoiceNumber} dostanie w KSeF korektę do zera."
                InvoiceTreatment.KEEP -> if (plan.cloneDocuments) {
                    steps += "Faktura ${invoice.invoiceNumber} zostaje bez zmian, zmienia się tylko forma płatności w CRM."
                }
                null -> Unit
            }
        }
        if (plan.issueInvoice) {
            steps += if (plan.invoiceToReceipt) {
                "Faktura do paragonu na ${m(plan.totalGrossAfter)}. Paragon zostaje, sprzedaż nie liczy się drugi raz."
            } else {
                "Nowa faktura na ${m(plan.totalGrossAfter)}."
            }
        }
        when {
            plan.customerDifference > 0 -> steps += "Klient dopłaca ${m(plan.customerDifference)}."
            plan.customerDifference < 0 -> steps += "Zwrot dla klienta: ${m(-plan.customerDifference)}."
        }
        if (plan.cashDelta != 0L) {
            val sign = if (plan.cashDelta > 0) "+" else "−"
            steps += "Stan kasy zmieni się o $sign${m(kotlin.math.abs(plan.cashDelta))}."
        }
        return steps
    }

    // ── Wykonanie ──────────────────────────────────────────────────────────────

    suspend fun execute(command: SettlementCorrectionCommand): SettlementCorrectionResult = withContext(Dispatchers.IO) {
        val settings = settingsRepository.findById(command.studioId.value).orElse(null)
        val sendToKsef = settings?.ksefAutoSendDefault ?: true

        val applied = transactionTemplate.execute { applyInTransaction(command, sendToKsef) }!!

        // ── KSeF po commicie: najpierw korekty, potem nowa faktura ────────────
        val correction = applied.correction
        val errors = mutableListOf<String>()
        var correctionsOk = true
        applied.invoicesToCorrect.forEach { original ->
            try {
                val kor = issueCorrectionHandler.handle(
                    IssueCorrectionCommand(
                        studioId = command.studioId,
                        userId = command.userId,
                        originalInvoiceId = original.id,
                        reason = command.reason?.trim()?.ifBlank { null } ?: "Poprawka rozliczenia wizyty",
                        items = null,
                        exemptionLegalBasis = command.exemptionLegalBasis
                    )
                )
                correction.ksefCorrectionInvoiceId = kor.id
            } catch (e: Exception) {
                correctionsOk = false
                log.error("Korekta faktury {} w poprawce rozliczenia {} nie powstała", original.invoiceNumber, correction.id, e)
                errors += "Korekta faktury ${original.invoiceNumber} nie powstała: ${e.message}. Wystaw ją w module KSeF."
            }
        }
        if (applied.invoiceCommand != null) {
            if (!correctionsOk) {
                // Nowa faktura przy żywej starej to podwójna sprzedaż w KSeF — lepiej brak niż dwie.
                errors += "Nowa faktura nie została wystawiona, bo korekta poprzedniej się nie udała."
            } else {
                try {
                    val invoice = issueInvoiceHandler.handle(applied.invoiceCommand)
                    correction.newKsefInvoiceId = invoice.id
                    applied.invoiceDocumentId?.let { docId ->
                        documentRepository.findById(docId).ifPresent { doc ->
                            doc.ksefRevenueInvoiceId = invoice.id
                            documentRepository.save(doc)
                        }
                    }
                } catch (e: Exception) {
                    log.error("Nowa faktura w poprawce rozliczenia {} nie powstała", correction.id, e)
                    errors += "Nowa faktura nie powstała: ${e.message}. Wystaw ją z wizyty albo w module KSeF."
                }
            }
        }
        if (errors.isNotEmpty()) correction.ksefError = errors.joinToString("\n")
        correctionRepository.save(correction)

        auditService.log(applied.audit)
        SettlementCorrectionResult(correction.id, applied.steps, correction.ksefError)
    }

    private class Applied(
        val correction: VisitSettlementCorrectionEntity,
        val steps: List<String>,
        val invoicesToCorrect: List<KsefRevenueInvoiceEntity>,
        val invoiceCommand: IssueRevenueInvoiceCommand?,
        val invoiceDocumentId: UUID?,
        val audit: LogAuditCommand
    )

    private fun applyInTransaction(command: SettlementCorrectionCommand, sendToKsef: Boolean): Applied {
        visitRepository.lockForUpdate(command.visitId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Wizyta nie została znaleziona")
        val visit = loadVisit(command.studioId, command.visitId)
        val state = loadState(command.studioId.value, visit.id.value)
        // Plan liczony drugi raz pod blokadą: podgląd sprzed chwili mógł się zestarzeć.
        val plan = plan(visit, state, command)
        plan.blockReason?.let { throw ValidationException(it) }

        val now = Instant.now()
        val correctionId = UUID.randomUUID()
        val after = visit.correctSettledPrices(command.prices, command.userId, now)
        if (plan.itemsChanged) visitRepository.save(VisitEntity.fromDomain(after))

        val customer = customerRepository.findByIdAndStudioId(visit.customerId.value, command.studioId.value)

        // Storna i zastąpienie — dokument zostaje, obok staje jego korekta.
        plan.documentsToReplace.forEach { old ->
            createDocumentHandler.handle(
                baseDocument(command, visit, customer, correctionId).copy(
                    documentType = DocumentType.CORRECTION,
                    paymentMethod = old.paymentMethod,
                    totalNet = -old.totalNet,
                    totalVat = -old.totalVat,
                    totalGross = -old.totalGross,
                    dueDate = LocalDate.now(),
                    description = "Korekta ${old.documentNumber}: poprawka rozliczenia wizyty #${visit.visitNumber}",
                    counterpartyName = old.counterpartyName,
                    counterpartyNip = old.counterpartyNip,
                    correctsDocumentId = old.id,
                    ksefRevenueInvoiceId = old.ksefRevenueInvoiceId,
                    statusOverride = old.status
                )
            )
            old.supersededAt = now
            old.settlementCorrectionId = correctionId
            old.updatedBy = command.userId.value
            old.updatedAt = now
            documentRepository.save(old)
        }

        if (plan.cloneDocuments) {
            plan.documentsToReplace.forEach { old ->
                createDocumentHandler.handle(
                    baseDocument(command, visit, customer, correctionId).copy(
                        documentType = old.documentType,
                        totalNet = old.totalNet,
                        totalVat = old.totalVat,
                        totalGross = old.totalGross,
                        description = old.description,
                        counterpartyName = old.counterpartyName,
                        counterpartyNip = old.counterpartyNip,
                        ksefRevenueInvoiceId = old.ksefRevenueInvoiceId
                    )
                )
            }
            // Faktura zostaje, ale jej status płatności idzie za nową formą płatności.
            val paid = command.paymentMethod != PaymentMethod.TRANSFER
            state.activeInvoices.filter { plan.invoiceTreatments[it.id] == InvoiceTreatment.KEEP }.forEach { invoice ->
                invoice.paymentStatus = if (paid) "PAID" else "PENDING"
                invoice.paidAt = if (paid) invoice.paidAt ?: now else null
                invoice.paymentDueDate = command.dueDate ?: invoice.paymentDueDate
                invoice.updatedAt = now
                invoiceRepository.save(invoice)
            }
        }

        // Faktury, które nie weszły do sesji KSeF — anulowanie warunkowe.
        var cancelledId: UUID? = null
        state.activeInvoices.filter { plan.invoiceTreatments[it.id] == InvoiceTreatment.CANCEL }.forEach { invoice ->
            val updated = invoiceRepository.cancelIfNotInSession(
                invoice.id, command.studioId.value, command.userId.value, KsefRevenueStatus.CANCELLABLE, now
            )
            if (updated == 0) throw ConflictException(
                "Faktura ${invoice.invoiceNumber} właśnie wysyła się do KSeF. Poczekaj na odpowiedź i spróbuj ponownie."
            )
            cancelledId = invoice.id
        }

        val buyer = effectiveBuyer(command, state, visit)
        var invoiceDocumentId: UUID? = null
        var invoiceCommand: IssueRevenueInvoiceCommand? = null
        if (plan.issueInvoice) {
            invoiceCommand = invoiceCommand(command, after, buyer, plan.invoiceToReceipt, sendToKsef)
        }
        plan.newDocumentType?.let { type ->
            val totals = if (type == DocumentType.INVOICE) {
                invoiceOrchestrator.computeInvoiceTotals(invoiceItems(after))
                    .let { Triple(it.net, it.vat, it.gross) }
            } else {
                Triple(plan.totalNetAfter, plan.totalGrossAfter - plan.totalNetAfter, plan.totalGrossAfter)
            }
            val document = createDocumentHandler.handle(
                baseDocument(command, visit, customer, correctionId).copy(
                    documentType = type,
                    totalNet = totals.first,
                    totalVat = totals.second,
                    totalGross = totals.third,
                    counterpartyName = if (type == DocumentType.INVOICE) buyer.name else customerName(customer),
                    counterpartyNip = if (type == DocumentType.INVOICE) buyer.normalizedNip else null
                )
            )
            if (type == DocumentType.INVOICE) invoiceDocumentId = document.id.value
        }

        val entity = VisitSettlementCorrectionEntity(
            id = correctionId,
            studioId = command.studioId.value,
            visitId = visit.id.value,
            reason = command.reason?.trim()?.ifBlank { null }?.take(500),
            totalNetBefore = plan.totalNetBefore,
            totalGrossBefore = plan.totalGrossBefore,
            totalNetAfter = plan.totalNetAfter,
            totalGrossAfter = plan.totalGrossAfter,
            paymentMethodBefore = state.paymentMethod,
            paymentMethodAfter = command.paymentMethod,
            documentTypeBefore = state.documentType,
            documentTypeAfter = command.documentType,
            ksefAction = plan.ksefAction,
            cancelledKsefInvoiceId = cancelledId,
            steps = plan.steps.joinToString("\n"),
            servicesBefore = objectMapper.writeValueAsString(servicesSnapshot(visit)),
            servicesAfter = objectMapper.writeValueAsString(servicesSnapshot(after)),
            createdBy = command.userId.value,
            createdByName = command.userName,
            createdAt = now
        )
        correctionRepository.save(entity)

        val audit = LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName,
            module = AuditModule.VISIT,
            entityId = visit.id.value.toString(),
            entityDisplayName = visit.auditDisplayName,
            action = AuditAction.SETTLEMENT_CORRECTED,
            changes = buildList {
                if (plan.totalGrossBefore != plan.totalGrossAfter) add(
                    FieldChange("totalGross", auditMoney(plan.totalGrossBefore), auditMoney(plan.totalGrossAfter), AuditValueType.MONEY)
                )
                if (state.paymentMethod != command.paymentMethod) add(
                    FieldChange("paymentMethod", state.paymentMethod?.displayName, command.paymentMethod.displayName)
                )
                if (state.documentType != command.documentType) add(
                    FieldChange("documentType", state.documentType?.displayName, command.documentType.displayName)
                )
            },
            metadata = buildMap {
                put("ksefAction", plan.ksefAction.name)
                command.reason?.trim()?.ifBlank { null }?.let { put("reason", it) }
            },
            context = AuditContext(visitId = visit.id, visitName = visit.auditDisplayName),
            amount = AuditAmount.ofGrosz(plan.totalGrossAfter),
            correlationId = correctionId
        )

        return Applied(
            correction = entity,
            steps = plan.steps,
            invoicesToCorrect = state.activeInvoices.filter { plan.invoiceTreatments[it.id] == InvoiceTreatment.CORRECT_TO_ZERO },
            invoiceCommand = invoiceCommand,
            invoiceDocumentId = invoiceDocumentId,
            audit = audit
        )
    }

    // ── Pomocnicze ─────────────────────────────────────────────────────────────

    private fun baseDocument(
        command: SettlementCorrectionCommand,
        visit: Visit,
        customer: CustomerEntity?,
        correctionId: UUID
    ) = CreateFinancialDocumentCommand(
        studioId = command.studioId,
        userId = command.userId,
        userDisplayName = command.userName,
        source = DocumentSource.VISIT,
        visitId = visit.id,
        vehicleBrand = visit.brandSnapshot,
        vehicleModel = visit.modelSnapshot,
        customerFirstName = customer?.firstName,
        customerLastName = customer?.lastName,
        documentType = DocumentType.RECEIPT,
        direction = DocumentDirection.INCOME,
        paymentMethod = command.paymentMethod,
        totalNet = 0,
        totalVat = 0,
        totalGross = 0,
        issueDate = LocalDate.now(),
        dueDate = command.dueDate ?: LocalDate.now(),
        description = "Wizyta #${visit.visitNumber}: poprawka rozliczenia",
        counterpartyName = null,
        counterpartyNip = null,
        settlementCorrectionId = correctionId
    )

    private fun customerName(customer: CustomerEntity?): String? =
        customer?.companyName?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(customer?.firstName, customer?.lastName).joinToString(" ").ifBlank { null }

    /** Nabywca: z formularza, inaczej z obowiązującej faktury, inaczej z kartoteki klienta. */
    private fun effectiveBuyer(command: SettlementCorrectionCommand, state: SettlementState, visit: Visit): SettlementBuyer {
        command.buyer?.let { return it }
        state.activeInvoices.lastOrNull()?.let {
            return SettlementBuyer(it.buyerNip, it.buyerName, it.buyerAddressLine1, it.buyerAddressLine2, it.buyerEmail)
        }
        val customer = customerRepository.findByIdAndStudioId(visit.customerId.value, command.studioId.value)
        return SettlementBuyer(
            nip = customer?.companyNip,
            name = customer?.companyName?.takeIf { it.isNotBlank() && customer.companyNip != null } ?: customerName(customer),
            email = customer?.email
        )
    }

    private fun sameBuyer(invoice: KsefRevenueInvoiceEntity, buyer: SettlementBuyer?): Boolean {
        if (buyer == null) return true
        return (invoice.buyerNip ?: "") == (buyer.normalizedNip ?: "") &&
            (invoice.buyerName ?: "").trim() == (buyer.name ?: "").trim()
    }

    private fun invoiceItems(visit: Visit): List<CompleteInvoiceItem> =
        visit.serviceItems.filter { it.countsTowardSettlement }.map { item ->
            // Pozycja wpisana od brutto idzie na fakturę w brutto — kwota wpisana przez
            // człowieka nie może się przeliczyć (CLAUDE.md §1).
            if (item.basePriceGross != null) {
                CompleteInvoiceItem(item.serviceName, BigDecimal.ONE, unitPriceGross = item.finalPriceGross.amountInCents, vatRate = vatCode(item.vatRate))
            } else {
                CompleteInvoiceItem(item.serviceName, BigDecimal.ONE, unitPriceNet = item.finalPriceNet.amountInCents, vatRate = vatCode(item.vatRate))
            }
        }

    private fun invoiceCommand(
        command: SettlementCorrectionCommand,
        visit: Visit,
        buyer: SettlementBuyer,
        invoiceToReceipt: Boolean,
        sendToKsef: Boolean
    ) = IssueRevenueInvoiceCommand(
        studioId = command.studioId,
        userId = command.userId,
        buyer = RevenueInvoiceBuyerCommand(
            nip = buyer.normalizedNip,
            name = buyer.name?.trim()?.ifBlank { null },
            addressLine1 = buyer.addressLine1,
            addressLine2 = buyer.addressLine2,
            email = buyer.email
        ),
        items = invoiceItems(visit).map {
            RevenueInvoiceItemCommand(
                name = it.name.trim(),
                quantity = it.quantity,
                unitPriceNet = it.unitPriceNet,
                unitPriceGross = it.unitPriceGross,
                vatRate = it.vatRate
            )
        },
        saleDate = visit.pickupDate?.atZone(java.time.ZoneId.of("Europe/Warsaw"))?.toLocalDate() ?: LocalDate.now(),
        paymentForm = invoiceOrchestrator.toPaymentForm(command.paymentMethod)?.name,
        isPaid = command.paymentMethod != PaymentMethod.TRANSFER,
        paymentDueDate = command.dueDate,
        exemptionLegalBasis = command.exemptionLegalBasis,
        visitId = visit.id.value,
        customerId = visit.customerId.value,
        description = "Wizyta #${visit.visitNumber}",
        sendToKsef = sendToKsef,
        invoiceToReceipt = invoiceToReceipt
    )

    private fun servicesSnapshot(visit: Visit) = visit.serviceItems.filter { it.countsTowardSettlement }.map {
        mapOf(
            "id" to it.id.value.toString(),
            "name" to it.serviceName,
            "net" to it.finalPriceNet.amountInCents,
            "gross" to it.finalPriceGross.amountInCents,
            "vatRate" to it.vatRate.rate,
            "grossTyped" to (it.basePriceGross != null)
        )
    }

    private fun VisitServiceItem.priceDiffers(other: VisitServiceItem): Boolean =
        finalPriceNet != other.finalPriceNet || finalPriceGross != other.finalPriceGross || vatRate != other.vatRate

    private fun vatCode(rate: VatRate): String = if (rate == VatRate.VAT_ZW) "zw" else rate.rate.toString()

    private companion object {
        /** Faktury, które nie istnieją jako sprzedaż: odrzucone przez KSeF i anulowane. */
        val DEAD = setOf(KsefRevenueStatus.REJECTED, KsefRevenueStatus.CANCELLED)
    }
}

/** Odpowiedź podglądu. */
data class SettlementPreview(
    val steps: List<String>,
    val blockReason: String?,
    val totalGrossBefore: Long,
    val totalGrossAfter: Long,
    val customerDifference: Long,
    val cashDelta: Long
)
