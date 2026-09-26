package pl.detailing.crm.visit.transitions.complete

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
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
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceEntity
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import pl.detailing.crm.ksef.revenue.issue.IssueRevenueInvoiceHandler
import pl.detailing.crm.shared.FinancialDocumentId
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.RecordingTransactionManager
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.subscription.entitlement.capability.CapabilityService
import pl.detailing.crm.visit.domain.VisitFixtures
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/**
 * Wydanie pojazdu z fakturą: prawdziwy [CompleteVisitHandler] pod orkiestratorem.
 *
 * Dwa błędy z produkcji, które ten test zamyka:
 *  - ponowne „Wydaj" z fakturą (dwuklik, ponowienie po zerwanym połączeniu) wystawiało
 *    drugą fakturę w KSeF i drugi paragon na resztę - ścieżka faktury nie sprawdzała,
 *    czy wizyta nie jest już wydana;
 *  - wydanie nie było jedną transakcją: błąd przy paragonie reszty zostawiał wizytę
 *    wydaną z dokumentem faktury, ale bez dokumentu na resztę kwoty.
 */
class CompleteVisitWithInvoiceAtomicityTest {

    private val studioId = StudioId.random()
    private val userId = UserId.random()

    private val visitRepository: VisitRepository = mockk()
    private val customerRepository: CustomerRepository = mockk { every { findByIdAndStudioId(any(), any()) } returns null }
    private val createFinancialDocumentHandler: CreateFinancialDocumentHandler = mockk()
    private val financialDocumentRepository: FinancialDocumentRepository = mockk(relaxed = true)
    private val issueInvoiceHandler: IssueRevenueInvoiceHandler = mockk {
        every { validate(any()) } just runs
    }
    private val invoiceRepository: KsefRevenueInvoiceRepository = mockk()
    private val settingsRepository: StudioSettingsRepository = mockk {
        every { findById(any()) } returns Optional.of(
            StudioSettingsEntity(studioId = studioId.value, name = "Studio Detailingu", taxId = "5261040828")
        )
    }
    private val capabilityService: CapabilityService = mockk(relaxed = true) {
        every { hasCapability(any(), any()) } returns true
    }
    private val transactions = RecordingTransactionManager()

    private val handler = CompleteVisitHandler(
        visitRepository, customerRepository, mockk(relaxed = true), createFinancialDocumentHandler,
        capabilityService, mockk(relaxed = true), financialDocumentRepository, transactions.template()
    )

    private val orchestrator = CompleteVisitInvoiceOrchestrator(
        handler, issueInvoiceHandler, createFinancialDocumentHandler, financialDocumentRepository,
        settingsRepository, visitRepository, customerRepository, capabilityService,
        auditService = mockk(relaxed = true), invoiceRepository = invoiceRepository
    )

    /** Wizyta za 123,00 zł brutto; faktura na 61,50 zł, reszta 61,50 zł paragonem gotówkowym. */
    private fun givenVisit(status: VisitStatus): VisitId {
        val visit = VisitFixtures.visit(studioId = studioId, status = status)
        every { visitRepository.lockForUpdate(visit.id.value, studioId.value) } returns visit.id.value
        every { visitRepository.findByIdAndStudioIdWithPhotos(visit.id.value, studioId.value) } answers {
            VisitEntity.fromDomain(visit)
        }
        every { visitRepository.save(any()) } answers { firstArg() }
        return visit.id
    }

    private fun command(visitId: VisitId) = CompleteVisitCommand(
        studioId = studioId, userId = userId, visitId = visitId,
        paymentMethod = PaymentMethod.CARD, documentType = DocumentType.INVOICE
    )

    private val halfInvoice = CompleteInvoiceDetails(
        items = listOf(CompleteInvoiceItem(name = "Mycie", unitPriceNet = 5_000, vatRate = "23")),
        buyer = CompleteInvoiceBuyer(name = "Jan Kowalski"),
        remainderPaymentMethod = PaymentMethod.CASH
    )

    private fun document(number: String) = FinancialDocument(
        id = FinancialDocumentId(UUID.randomUUID()), studioId = studioId, source = DocumentSource.VISIT,
        visitId = null, vehicleBrand = null, vehicleModel = null, customerFirstName = null, customerLastName = null,
        documentNumber = number, documentType = DocumentType.RECEIPT, direction = DocumentDirection.INCOME,
        status = DocumentStatus.PAID, paymentMethod = PaymentMethod.CASH,
        totalNet = Money(5_000), totalVat = Money(1_150), totalGross = Money(6_150), currency = "PLN",
        issueDate = LocalDate.now(), dueDate = null, paidAt = Instant.now(), description = null,
        counterpartyName = null, counterpartyNip = null, createdBy = userId, updatedBy = userId,
        createdAt = Instant.now(), updatedAt = Instant.now()
    )

    @Test
    fun `wizyta juz wydana - nie wystawia drugiej faktury ani paragonu, zwraca fakture z pierwszego wydania`() = runBlocking {
        val visitId = givenVisit(VisitStatus.COMPLETED)
        val firstInvoice = mockk<KsefRevenueInvoiceEntity>(relaxed = true)
        every { invoiceRepository.findFirstByVisitIdAndStudioIdOrderByCreatedAtAsc(visitId.value, studioId.value) } returns firstInvoice

        val result = orchestrator.handle(command(visitId), halfInvoice)

        assertTrue(result.completion.alreadyInTargetState)
        assertSame(firstInvoice, result.ksefInvoice)
        verify(exactly = 0) { issueInvoiceHandler.handle(any()) }
        verify(exactly = 0) { createFinancialDocumentHandler.handle(any()) }
        verify(exactly = 0) { visitRepository.save(any()) }
    }

    @Test
    fun `wyscig przegrany na blokadzie - druga faktura nie powstaje`() = runBlocking {
        // Odczyt przed transakcją widzi wizytę gotową do wydania, ale zanim dostaliśmy
        // blokadę, ktoś inny ją wydał - pod blokadą wiersz jest już COMPLETED.
        val ready = VisitFixtures.visit(studioId = studioId, status = VisitStatus.READY_FOR_PICKUP)
        val completed = ready.copy(status = VisitStatus.COMPLETED)
        every { visitRepository.lockForUpdate(ready.id.value, studioId.value) } returns ready.id.value
        every { visitRepository.findByIdAndStudioIdWithPhotos(ready.id.value, studioId.value) } returnsMany
            listOf(VisitEntity.fromDomain(ready), VisitEntity.fromDomain(completed))
        every { invoiceRepository.findFirstByVisitIdAndStudioIdOrderByCreatedAtAsc(any(), any()) } returns null

        val result = orchestrator.handle(command(ready.id), halfInvoice)

        assertTrue(result.completion.alreadyInTargetState)
        assertNull(result.ksefInvoice)
        verify(exactly = 0) { issueInvoiceHandler.handle(any()) }
        verify(exactly = 0) { createFinancialDocumentHandler.handle(any()) }
    }

    @Test
    fun `blad przy paragonie reszty wycofuje cale wydanie i nie wystawia faktury`() {
        val visitId = givenVisit(VisitStatus.READY_FOR_PICKUP)
        every { createFinancialDocumentHandler.handle(match { it.documentType == DocumentType.INVOICE }) } returns
            document("FAK/2026/0001")
        every { createFinancialDocumentHandler.handle(match { it.documentType == DocumentType.RECEIPT }) } throws
            IllegalStateException("kasa niedostępna")

        assertThrows<IllegalStateException> { runBlocking { orchestrator.handle(command(visitId), halfInvoice) } }

        assertEquals(1, transactions.rollbacks, "wydanie, dokument faktury i paragon to jedna transakcja")
        assertEquals(0, transactions.commits)
        verify(exactly = 0) { issueInvoiceHandler.handle(any()) }
    }

    @Test
    fun `swieze wydanie - dokument faktury i paragon reszty w jednej transakcji, faktura KSeF po commicie`() = runBlocking {
        val visitId = givenVisit(VisitStatus.READY_FOR_PICKUP)
        val issued = mutableListOf<CreateFinancialDocumentCommand>()
        every { createFinancialDocumentHandler.handle(capture(issued)) } answers {
            val cmd = firstArg<CreateFinancialDocumentCommand>()
            document(if (cmd.documentType == DocumentType.RECEIPT) "PAR/2026/0007" else "FAK/2026/0001")
        }
        val invoice = mockk<KsefRevenueInvoiceEntity>(relaxed = true)
        every { issueInvoiceHandler.handle(any()) } answers {
            assertEquals(1, transactions.commits, "faktura KSeF dopiero po commicie wydania")
            invoice
        }

        val result = orchestrator.handle(command(visitId), halfInvoice)

        assertFalse(result.completion.alreadyInTargetState)
        assertSame(invoice, result.ksefInvoice)
        assertEquals("PAR/2026/0007", result.remainderDocumentNumber)
        assertEquals(1, transactions.begun)
        assertEquals(listOf(DocumentType.INVOICE, DocumentType.RECEIPT), issued.map { it.documentType })
        assertEquals(6_150, issued[1].totalGross, "reszta = 123,00 − 61,50 zł")
    }

    @Test
    fun `wydanie z paragonem - blad dokumentu wycofuje wydanie i nie zostawia wpisu w Aktywnosci`() {
        val auditService = mockk<pl.detailing.crm.audit.domain.AuditService>(relaxed = true)
        val receiptHandler = CompleteVisitHandler(
            visitRepository, customerRepository, auditService, createFinancialDocumentHandler,
            capabilityService, mockk(relaxed = true), financialDocumentRepository, transactions.template()
        )
        val visitId = givenVisit(VisitStatus.READY_FOR_PICKUP)
        every { createFinancialDocumentHandler.handle(any()) } throws IllegalStateException("kasa niedostępna")

        assertThrows<IllegalStateException> {
            runBlocking {
                receiptHandler.handle(command(visitId).copy(documentType = DocumentType.RECEIPT, paymentMethod = PaymentMethod.CASH))
            }
        }

        assertEquals(1, transactions.rollbacks)
        assertEquals(0, transactions.commits)
        io.mockk.coVerify(exactly = 0) { auditService.log(any()) }
    }
}
