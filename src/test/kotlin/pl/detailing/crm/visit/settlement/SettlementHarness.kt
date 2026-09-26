package pl.detailing.crm.visit.settlement

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
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
import pl.detailing.crm.shared.RecordingTransactionManager
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VatRate
import pl.detailing.crm.shared.VisitServiceItemId
import pl.detailing.crm.shared.VisitServiceStatus
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.subscription.entitlement.capability.CapabilityService
import pl.detailing.crm.visit.domain.SettledPrice
import pl.detailing.crm.visit.domain.Visit
import pl.detailing.crm.visit.domain.VisitFixtures
import pl.detailing.crm.visit.domain.VisitServiceItem
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.transitions.complete.CompleteVisitInvoiceOrchestrator
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/**
 * Wspólne środowisko testów poprawki rozliczenia: repozytoria w pamięci, prawdziwa
 * walidacja faktury (IssueRevenueInvoiceHandler.validate) i prawdziwy kalkulator sum
 * faktury, a wszystko, co sieciowe (wysyłka KSeF), jako atrapy.
 *
 * Kasa jest liczona z utworzonych dokumentów tak samo jak w CreateFinancialDocumentHandler
 * (gotówka opłacona: przychód na plus, storno na minus), więc test może porównać zapowiedź
 * z podglądu (cashDelta) z tym, co naprawdę trafiło do kasy.
 */
class SettlementHarness {
    val studioId: StudioId = StudioId.random()
    val userId: UserId = UserId.random()

    /** Domyślnie wizyta za 615,00 zł brutto (500,00 netto, 23%). */
    var items: MutableList<VisitServiceItem> = mutableListOf(VisitFixtures.serviceItem(finalPriceNet = 50_000, finalPriceGross = 61_500))
    var visit: Visit = VisitFixtures.visit(studioId = studioId, status = VisitStatus.COMPLETED, items = items)

    val documents = mutableListOf<FinancialDocumentEntity>()
    val invoices = mutableListOf<KsefRevenueInvoiceEntity>()
    val created = mutableListOf<CreateFinancialDocumentCommand>()
    val savedVisits = mutableListOf<VisitEntity>()
    val cancelled = mutableListOf<UUID>()
    val savedCorrections = mutableListOf<VisitSettlementCorrectionEntity>()
    val audits = mutableListOf<LogAuditCommand>()
    val issuedInvoices = mutableListOf<IssueRevenueInvoiceCommand>()
    val issuedCorrections = mutableListOf<IssueCorrectionCommand>()

    var hasFinance = true
    var companyComplete = true
    var ksefAutoSend = true
    var customer: CustomerEntity? = null
    /** 0 = wysyłka zajęła fakturę między planem a anulowaniem. */
    var cancelResult = 1
    var failCorrection: Exception? = null
    var failInvoice: Exception? = null
    var existingCorrection = false

    /** Kasa: suma ruchów gotówkowych z dokumentów utworzonych w teście. */
    val cashMovement: Long
        get() = created.filter { it.paymentMethod == PaymentMethod.CASH && statusOf(it) == DocumentStatus.PAID }
            .sumOf { it.totalGross }

    private fun statusOf(c: CreateFinancialDocumentCommand) = c.statusOverride ?: c.paymentMethod.defaultStatus()

    val transactions = RecordingTransactionManager()

    val visitRepository: VisitRepository = mockk {
        every { lockForUpdate(any(), any()) } answers { if (firstArg<UUID>() == visit.id.value) firstArg() else null }
        every { findByIdAndStudioIdWithPhotos(any(), any()) } answers {
            if (firstArg<UUID>() == visit.id.value && secondArg<UUID>() == studioId.value) VisitEntity.fromDomain(visit) else null
        }
        every { save(any()) } answers {
            firstArg<VisitEntity>().also { savedVisits += it; visit = it.toDomain() }
        }
    }
    val documentRepository: FinancialDocumentRepository = mockk {
        every { findAllByVisitIdAndStudioIdAndDeletedAtIsNull(any(), any()) } answers {
            documents.filter { it.visitId == firstArg<UUID>() && it.deletedAt == null }
        }
        every { save(any()) } answers { firstArg() }
        every { findById(any()) } answers { Optional.ofNullable(documents.firstOrNull { it.id == firstArg<UUID>() }) }
    }
    val invoiceRepository: KsefRevenueInvoiceRepository = mockk {
        every { findByStudioIdAndVisitIdOrderByCreatedAtAsc(any(), any()) } answers {
            invoices.filter { it.visitId == secondArg<UUID>() }
        }
        every { existsByStudioIdAndOriginalInvoiceIdAndKsefStatusNot(any(), any(), any()) } answers { existingCorrection }
        every { cancelIfNotInSession(any(), any(), any(), any(), any()) } answers {
            if (cancelResult == 1) {
                cancelled += firstArg<UUID>()
                invoices.first { it.id == firstArg<UUID>() }.ksefStatus = KsefRevenueStatus.CANCELLED
            }
            cancelResult
        }
        every { save(any()) } answers { firstArg() }
    }
    val correctionRepository: VisitSettlementCorrectionRepository = mockk {
        every { save(any()) } answers { firstArg<VisitSettlementCorrectionEntity>().also { e -> if (e !in savedCorrections) savedCorrections += e } }
        every { findByStudioIdAndVisitIdOrderByCreatedAtDesc(any(), any()) } answers { savedCorrections.reversed() }
    }
    private val createHandler: CreateFinancialDocumentHandler = mockk {
        every { handle(any()) } answers {
            val cmd = firstArg<CreateFinancialDocumentCommand>()
            created += cmd
            val doc = FinancialDocumentEntity(
                id = UUID.randomUUID(), studioId = studioId.value, source = cmd.source, visitId = cmd.visitId?.value,
                vehicleBrand = null, vehicleModel = null, customerFirstName = null, customerLastName = null,
                documentNumber = "${cmd.documentType.prefix}/2026/${documents.size + 1}".padEnd(4),
                documentType = cmd.documentType, direction = cmd.direction, status = statusOf(cmd),
                paymentMethod = cmd.paymentMethod, totalNet = cmd.totalNet, totalVat = cmd.totalVat,
                totalGross = cmd.totalGross, issueDate = cmd.issueDate, dueDate = cmd.dueDate, paidAt = Instant.now(),
                description = cmd.description, counterpartyName = cmd.counterpartyName, counterpartyNip = cmd.counterpartyNip,
                createdBy = userId.value, updatedBy = userId.value, ksefRevenueInvoiceId = cmd.ksefRevenueInvoiceId,
                correctsDocumentId = cmd.correctsDocumentId, settlementCorrectionId = cmd.settlementCorrectionId
            )
            documents += doc
            doc.toDomain()
        }
    }
    private val settingsRepository: StudioSettingsRepository = mockk {
        every { findById(any()) } answers {
            Optional.of(
                StudioSettingsEntity(
                    studioId = studioId.value,
                    name = if (companyComplete) "Studio Detailingu" else null,
                    taxId = if (companyComplete) "5261040828" else null
                ).also { it.ksefAutoSendDefault = ksefAutoSend }
            )
        }
    }
    private val issueInvoiceHandler: IssueRevenueInvoiceHandler = spyk(
        IssueRevenueInvoiceHandler(mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk())
    ) {
        every { handle(any()) } answers {
            failInvoice?.let { throw it }
            val cmd = firstArg<IssueRevenueInvoiceCommand>()
            issuedInvoices += cmd
            invoice(KsefRevenueStatus.PENDING, gross = 0, number = "FV/2026/9${issuedInvoices.size}").also {
                it.invoiceToReceipt = cmd.invoiceToReceipt
            }
        }
    }
    private val correctionHandler: IssueCorrectionHandler = mockk {
        every { handle(any()) } answers {
            failCorrection?.let { throw it }
            val cmd = firstArg<IssueCorrectionCommand>()
            issuedCorrections += cmd
            val original = invoices.first { it.id == cmd.originalInvoiceId }
            KsefRevenueInvoiceEntity(
                studioId = studioId.value, source = RevenueSource.CRM, ksefStatus = KsefRevenueStatus.PENDING,
                invoiceNumber = "FK/2026/000${issuedCorrections.size}", invoiceType = RevenueInvoiceType.KOR,
                originalInvoiceId = original.id, issueDate = LocalDate.now(), visitId = visit.id.value,
                totalNet = -original.totalNet, totalVat = -original.totalVat, totalGross = -original.totalGross
            ).also { invoices += it }
        }
    }
    private val capabilityService: CapabilityService = mockk(relaxed = true) {
        every { hasCapability(any(), any()) } answers { hasFinance }
    }
    private val customerRepository: CustomerRepository = mockk {
        every { findByIdAndStudioId(any(), any()) } answers { customer }
    }
    private val auditService: AuditService = mockk(relaxed = true) {
        io.mockk.coEvery { log(any()) } answers { audits += firstArg<LogAuditCommand>() }
    }

    val service = SettlementCorrectionService(
        visitRepository, documentRepository, invoiceRepository, correctionRepository, createHandler,
        issueInvoiceHandler, correctionHandler,
        CompleteVisitInvoiceOrchestrator(
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true)
        ),
        customerRepository, settingsRepository, capabilityService, auditService, ObjectMapper(),
        transactions.template()
    )

    // ── Budowanie stanu ──────────────────────────────────────────────────────

    fun withItems(vararg newItems: VisitServiceItem) {
        items = newItems.toMutableList()
        visit = visit.copy(serviceItems = items)
    }

    val item: VisitServiceItem get() = visit.serviceItems.first()

    fun document(
        type: DocumentType,
        method: PaymentMethod,
        gross: Long = 61_500,
        net: Long = 50_000,
        ksefId: UUID? = null,
        status: DocumentStatus = method.defaultStatus(),
        direction: DocumentDirection = DocumentDirection.INCOME
    ) = FinancialDocumentEntity(
        id = UUID.randomUUID(), studioId = studioId.value, source = DocumentSource.VISIT, visitId = visit.id.value,
        vehicleBrand = null, vehicleModel = null, customerFirstName = null, customerLastName = null,
        documentNumber = "${type.prefix}/2026/000${documents.size + 1}", documentType = type,
        direction = direction, status = status, paymentMethod = method, totalNet = net, totalVat = gross - net,
        totalGross = gross, issueDate = LocalDate.now(), dueDate = null, paidAt = Instant.now(), description = null,
        counterpartyName = null, counterpartyNip = null, createdBy = userId.value, updatedBy = userId.value,
        ksefRevenueInvoiceId = ksefId
    ).also { documents += it }

    fun invoice(
        status: KsefRevenueStatus,
        gross: Long = 61_500,
        number: String = "FV/2026/0003",
        buyerNip: String? = null,
        buyerName: String? = "Jan Kowalski",
        toReceipt: Boolean = false,
        type: RevenueInvoiceType = RevenueInvoiceType.VAT,
        originalId: UUID? = null
    ) = KsefRevenueInvoiceEntity(
        studioId = studioId.value, source = RevenueSource.CRM, ksefStatus = status, invoiceNumber = number,
        invoiceType = type, originalInvoiceId = originalId, issueDate = LocalDate.now(), visitId = visit.id.value,
        buyerNip = buyerNip, buyerName = buyerName, totalNet = gross * 100 / 123, totalVat = gross - gross * 100 / 123,
        totalGross = gross, ksefNumber = if (status == KsefRevenueStatus.ACCEPTED) "5261040828-20260926-${number.hashCode()}" else null
    ).also { it.invoiceToReceipt = toReceipt; if (gross != 0L) invoices += it }

    /** Paragon gotówkowy 615,00 zł — najczęstsze wydanie. */
    fun cashReceipt() = document(DocumentType.RECEIPT, PaymentMethod.CASH)

    /** Faktura KSeF w danym stanie z dokumentem-adnotacją w CRM. */
    fun invoiceWithDocument(status: KsefRevenueStatus, method: PaymentMethod = PaymentMethod.CARD): Pair<KsefRevenueInvoiceEntity, FinancialDocumentEntity> {
        val inv = invoice(status)
        return inv to document(DocumentType.INVOICE, method, ksefId = inv.id)
    }

    // ── Polecenia ────────────────────────────────────────────────────────────

    fun price(net: Long, gross: Long? = null, rate: VatRate = VatRate.VAT_23, id: VisitServiceItemId = item.id) =
        mapOf(id to SettledPrice(net, gross, rate))

    fun command(
        type: DocumentType = DocumentType.RECEIPT,
        method: PaymentMethod = PaymentMethod.CASH,
        prices: Map<VisitServiceItemId, SettledPrice> = emptyMap(),
        dueDate: LocalDate? = null,
        buyer: SettlementBuyer? = SettlementBuyer(name = "Jan Kowalski"),
        exemption: String? = null,
        reason: String? = "Rabat po fakcie"
    ) = SettlementCorrectionCommand(
        studioId, userId, "Anna Kowalska", visit.id, prices, type, method, dueDate, buyer, exemption, reason
    )

    fun preview(cmd: SettlementCorrectionCommand) = service.preview(cmd)
    fun execute(cmd: SettlementCorrectionCommand) = runBlocking { service.execute(cmd) }

    fun serviceItem(net: Long, gross: Long, rate: VatRate = VatRate.VAT_23, grossTyped: Boolean = false,
                    status: VisitServiceStatus = VisitServiceStatus.CONFIRMED) =
        VisitFixtures.serviceItem(finalPriceNet = net, finalPriceGross = gross, status = status).copy(
            vatRate = rate, basePriceGross = if (grossTyped) pl.detailing.crm.shared.Money(gross) else null
        )
}
