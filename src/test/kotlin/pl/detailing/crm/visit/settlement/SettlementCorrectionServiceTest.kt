package pl.detailing.crm.visit.settlement

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.finance.document.CreateFinancialDocumentCommand
import pl.detailing.crm.finance.document.CreateFinancialDocumentHandler
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentSource
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.FinancialDocument
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
import pl.detailing.crm.shared.FinancialDocumentId
import pl.detailing.crm.shared.RecordingTransactionManager
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VatRate
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.subscription.entitlement.capability.CapabilityService
import pl.detailing.crm.visit.domain.SettledPrice
import pl.detailing.crm.visit.domain.Visit
import pl.detailing.crm.visit.domain.VisitFixtures
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.transitions.complete.CompleteVisitInvoiceOrchestrator
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/**
 * Poprawka rozliczenia wizyty wydanej — scenariusze ustalone z biznesem:
 * zmiana ceny paragonu, sama forma płatności, faktura niewysłana (anulowanie),
 * faktura przyjęta (korekta do zera + nowa), faktura w drodze (czekamy),
 * paragon → faktura bez zmiany kwot (faktura do paragonu), faktura → paragon.
 */
class SettlementCorrectionServiceTest {

    private val studioId = StudioId.random()
    private val userId = UserId.random()

    // Wizyta za 615,00 zł brutto (500,00 netto, 23%).
    private val item = VisitFixtures.serviceItem(finalPriceNet = 50_000, finalPriceGross = 61_500)
    private var visit: Visit = VisitFixtures.visit(studioId = studioId, status = VisitStatus.COMPLETED, items = listOf(item))

    private val documents = mutableListOf<FinancialDocumentEntity>()
    private val invoices = mutableListOf<KsefRevenueInvoiceEntity>()
    private val created = mutableListOf<CreateFinancialDocumentCommand>()
    private val savedVisits = mutableListOf<VisitEntity>()
    private val cancelled = mutableListOf<UUID>()

    private val visitRepository: VisitRepository = mockk {
        every { lockForUpdate(any(), any()) } answers { firstArg() }
        every { findByIdAndStudioIdWithPhotos(any(), any()) } answers { VisitEntity.fromDomain(visit) }
        every { save(any()) } answers { firstArg<VisitEntity>().also { savedVisits += it } }
    }
    private val documentRepository: FinancialDocumentRepository = mockk {
        every { findAllByVisitIdAndStudioIdAndDeletedAtIsNull(any(), any()) } answers { documents.toList() }
        every { save(any()) } answers { firstArg() }
        every { findById(any()) } answers { Optional.ofNullable(documents.firstOrNull { it.id == firstArg<UUID>() }) }
    }
    private val invoiceRepository: KsefRevenueInvoiceRepository = mockk {
        every { findByStudioIdAndVisitIdOrderByCreatedAtAsc(any(), any()) } answers { invoices.toList() }
        every { existsByStudioIdAndOriginalInvoiceIdAndKsefStatusNot(any(), any(), any()) } returns false
        every { cancelIfNotInSession(any(), any(), any(), any(), any()) } answers {
            cancelled += firstArg<UUID>(); 1
        }
        every { save(any()) } answers { firstArg() }
    }
    private val correctionRepository: VisitSettlementCorrectionRepository = mockk {
        every { save(any()) } answers { firstArg() }
    }
    private val createHandler: CreateFinancialDocumentHandler = mockk {
        every { handle(any()) } answers {
            val cmd = firstArg<CreateFinancialDocumentCommand>()
            created += cmd
            val entity = entity(cmd.documentType, cmd.paymentMethod, cmd.totalGross, cmd.totalNet, cmd.ksefRevenueInvoiceId)
            documents += entity
            entity.toDomain()
        }
    }
    private val issueInvoiceHandler: IssueRevenueInvoiceHandler = mockk(relaxed = true)
    private val correctionHandler: IssueCorrectionHandler = mockk()
    private val settings: StudioSettingsRepository = mockk {
        every { findById(any()) } returns Optional.of(
            StudioSettingsEntity(studioId = studioId.value, name = "Studio", taxId = "5261040828")
        )
    }
    private val capabilities: CapabilityService = mockk(relaxed = true) {
        every { hasCapability(any(), any()) } returns true
    }
    private val customers: CustomerRepository = mockk { every { findByIdAndStudioId(any(), any()) } returns null }
    private val transactions = RecordingTransactionManager()

    private val orchestrator = CompleteVisitInvoiceOrchestrator(
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true)
    )

    private val service = SettlementCorrectionService(
        visitRepository, documentRepository, invoiceRepository, correctionRepository, createHandler,
        issueInvoiceHandler, correctionHandler, orchestrator, customers, settings, capabilities,
        mockk(relaxed = true), ObjectMapper(), transactions.template()
    )

    private fun entity(
        type: DocumentType, method: PaymentMethod, gross: Long, net: Long = gross * 100 / 123, ksefId: UUID? = null
    ) = FinancialDocumentEntity(
        id = UUID.randomUUID(), studioId = studioId.value, source = DocumentSource.VISIT, visitId = visit.id.value,
        vehicleBrand = null, vehicleModel = null, customerFirstName = null, customerLastName = null,
        documentNumber = "${type.prefix}/2026/${documents.size + 1}", documentType = type,
        direction = DocumentDirection.INCOME, status = method.defaultStatus(), paymentMethod = method,
        totalNet = net, totalVat = gross - net, totalGross = gross, issueDate = LocalDate.now(), dueDate = null,
        paidAt = Instant.now(), description = null, counterpartyName = null, counterpartyNip = null,
        createdBy = userId.value, updatedBy = userId.value, ksefRevenueInvoiceId = ksefId
    )

    private fun invoice(status: KsefRevenueStatus, gross: Long = 61_500) = KsefRevenueInvoiceEntity(
        studioId = studioId.value, source = RevenueSource.CRM, ksefStatus = status, invoiceNumber = "FV/2026/0003",
        issueDate = LocalDate.now(), visitId = visit.id.value, buyerName = "Jan Kowalski",
        totalNet = 50_000, totalVat = gross - 50_000, totalGross = gross,
        ksefNumber = if (status == KsefRevenueStatus.ACCEPTED) "5261040828-20260926-ABC" else null
    ).also { invoices += it }

    private fun command(
        type: DocumentType = DocumentType.RECEIPT,
        method: PaymentMethod = PaymentMethod.CASH,
        prices: Map<pl.detailing.crm.shared.VisitServiceItemId, SettledPrice> = emptyMap()
    ) = SettlementCorrectionCommand(
        studioId, userId, "Anna Kowalska", visit.id, prices, type, method, null,
        SettlementBuyer(name = "Jan Kowalski"), null, "Rabat po fakcie"
    )

    private val newPrice = mapOf(item.id to SettledPrice(net = 40_650, gross = 50_000, vatRate = VatRate.VAT_23))

    @Test
    fun `nizsza cena paragonu gotowkowego - storno, nowy paragon, zwrot 115 zl z kasy`() = runBlocking {
        val receipt = entity(DocumentType.RECEIPT, PaymentMethod.CASH, 61_500).also { documents += it }

        val preview = service.preview(command(prices = newPrice))
        assertNull(preview.blockReason)
        assertEquals(-11_500, preview.customerDifference)
        assertEquals(-11_500, preview.cashDelta)

        service.execute(command(prices = newPrice))

        assertNotNull(receipt.supersededAt)
        assertEquals(listOf(DocumentType.CORRECTION, DocumentType.RECEIPT), created.map { it.documentType })
        assertEquals(-61_500, created[0].totalGross)
        assertEquals(receipt.id, created[0].correctsDocumentId)
        assertEquals(50_000, created[1].totalGross, "brutto wpisane 500,00 zł zostaje co do grosza")
        val savedItem = savedVisits.single().serviceItems.single()
        assertEquals(50_000, savedItem.finalPriceGross)
        assertEquals(1, transactions.commits)
    }

    @Test
    fun `sama forma platnosci - dokumenty wracaja z nowa forma, faktura zostaje`() = runBlocking {
        val inv = invoice(KsefRevenueStatus.ACCEPTED)
        documents += entity(DocumentType.INVOICE, PaymentMethod.TRANSFER, 61_500, 50_000, inv.id)

        service.execute(command(type = DocumentType.INVOICE, method = PaymentMethod.CASH))

        assertEquals(listOf(DocumentType.CORRECTION, DocumentType.INVOICE), created.map { it.documentType })
        assertEquals(PaymentMethod.CASH, created[1].paymentMethod)
        assertEquals(inv.id, created[1].ksefRevenueInvoiceId)
        assertEquals("PAID", inv.paymentStatus)
        verify(exactly = 0) { correctionHandler.handle(any()) }
        verify(exactly = 0) { issueInvoiceHandler.handle(any()) }
        assertTrue(savedVisits.isEmpty(), "ceny się nie zmieniły")
    }

    @Test
    fun `faktura niewyslana - anulowanie i nowa faktura po commicie`() = runBlocking {
        val inv = invoice(KsefRevenueStatus.NOT_SENT)
        documents += entity(DocumentType.INVOICE, PaymentMethod.CARD, 61_500, 50_000, inv.id)
        val issued = slot<IssueRevenueInvoiceCommand>()
        every { issueInvoiceHandler.handle(capture(issued)) } answers { invoice(KsefRevenueStatus.PENDING, 50_000) }

        service.execute(command(type = DocumentType.INVOICE, method = PaymentMethod.CARD, prices = newPrice))

        assertEquals(listOf(inv.id), cancelled)
        verify(exactly = 0) { correctionHandler.handle(any()) }
        assertEquals(50_000, issued.captured.items.single().unitPriceGross)
        assertFalse(issued.captured.invoiceToReceipt)
        val annotation = documents.last { it.documentType == DocumentType.INVOICE }
        assertEquals(invoices.last().id, annotation.ksefRevenueInvoiceId, "dokument faktury powiązany z nową fakturą")
    }

    @Test
    fun `faktura przyjeta, zmiana stawki VAT - korekta do zera i nowa faktura`() = runBlocking {
        val inv = invoice(KsefRevenueStatus.ACCEPTED)
        documents += entity(DocumentType.INVOICE, PaymentMethod.CARD, 61_500, 50_000, inv.id)
        val kor = slot<IssueCorrectionCommand>()
        every { correctionHandler.handle(capture(kor)) } answers { invoice(KsefRevenueStatus.PENDING, -61_500) }
        every { issueInvoiceHandler.handle(any()) } answers { invoice(KsefRevenueStatus.PENDING, 54_000) }
        // Netto zostaje (strona wpisana), stawka 23% → 8%.
        val at8 = mapOf(item.id to SettledPrice(net = 50_000, gross = null, vatRate = VatRate.VAT_8))

        val result = service.execute(command(type = DocumentType.INVOICE, method = PaymentMethod.CARD, prices = at8))

        assertEquals(inv.id, kor.captured.originalInvoiceId)
        assertNull(kor.captured.items, "korekta do zera")
        verify(exactly = 1) { issueInvoiceHandler.handle(any()) }
        assertNull(result.ksefError)
        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun `korekta KSeF sie nie udala - nowa faktura nie powstaje, blad zostaje w historii`() = runBlocking {
        val inv = invoice(KsefRevenueStatus.ACCEPTED)
        documents += entity(DocumentType.INVOICE, PaymentMethod.CARD, 61_500, 50_000, inv.id)
        every { correctionHandler.handle(any()) } throws ValidationException("Brak połączenia z KSeF")

        val result = service.execute(command(type = DocumentType.INVOICE, method = PaymentMethod.CARD, prices = newPrice))

        verify(exactly = 0) { issueInvoiceHandler.handle(any()) }
        assertTrue(result.ksefError!!.contains("FV/2026/0003"))
    }

    @Test
    fun `faktura w drodze do KSeF - poprawka czeka`() {
        val inv = invoice(KsefRevenueStatus.SUBMITTED)
        documents += entity(DocumentType.INVOICE, PaymentMethod.CARD, 61_500, 50_000, inv.id)

        val preview = service.preview(command(type = DocumentType.INVOICE, method = PaymentMethod.CARD, prices = newPrice))

        assertTrue(preview.blockReason!!.contains("czeka na odpowiedź KSeF"))
        assertThrows<ValidationException> {
            runBlocking { service.execute(command(type = DocumentType.INVOICE, method = PaymentMethod.CARD, prices = newPrice)) }
        }
        assertEquals(0, transactions.commits)
    }

    @Test
    fun `paragon na fakture bez zmiany kwot - faktura do paragonu, paragon zostaje`() = runBlocking {
        val receipt = entity(DocumentType.RECEIPT, PaymentMethod.CASH, 61_500).also { documents += it }
        val issued = slot<IssueRevenueInvoiceCommand>()
        every { issueInvoiceHandler.handle(capture(issued)) } answers { invoice(KsefRevenueStatus.PENDING) }

        service.execute(command(type = DocumentType.INVOICE, method = PaymentMethod.CASH))

        assertTrue(issued.captured.invoiceToReceipt)
        assertNull(receipt.supersededAt)
        assertTrue(created.isEmpty(), "bez storna i bez nowego dokumentu — sprzedaż jest na paragonie")
    }

    @Test
    fun `faktura przyjeta na paragon - korekta do zera i nowy paragon, bez nowej faktury`() = runBlocking {
        val inv = invoice(KsefRevenueStatus.ACCEPTED)
        documents += entity(DocumentType.INVOICE, PaymentMethod.CASH, 61_500, 50_000, inv.id)
        every { correctionHandler.handle(any()) } answers { invoice(KsefRevenueStatus.PENDING, -61_500) }

        service.execute(command(type = DocumentType.RECEIPT, method = PaymentMethod.CASH))

        verify(exactly = 1) { correctionHandler.handle(any()) }
        verify(exactly = 0) { issueInvoiceHandler.handle(any()) }
        assertEquals(listOf(DocumentType.CORRECTION, DocumentType.RECEIPT), created.map { it.documentType })
        assertEquals(inv.id, created[0].ksefRevenueInvoiceId, "storno faktury liczy się w łańcuchu KSeF, nie drugi raz")
    }

    @Test
    fun `nic sie nie zmienia - odmowa`() {
        documents += entity(DocumentType.RECEIPT, PaymentMethod.CASH, 61_500)

        assertTrue(service.preview(command()).blockReason!!.startsWith("Nic się nie zmienia"))
    }

    @Test
    fun `wizyta niewydana - odmowa`() {
        visit = visit.copy(status = VisitStatus.READY_FOR_PICKUP)

        assertTrue(service.preview(command(prices = newPrice)).blockReason!!.contains("wydanej"))
    }
}
