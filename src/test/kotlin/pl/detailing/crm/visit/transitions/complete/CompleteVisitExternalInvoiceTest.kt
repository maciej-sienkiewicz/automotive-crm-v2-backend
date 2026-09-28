package pl.detailing.crm.visit.transitions.complete

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.finance.document.CreateFinancialDocumentCommand
import pl.detailing.crm.finance.document.CreateFinancialDocumentHandler
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.FinancialDocument
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.external.ExternalInvoiceBuyer
import pl.detailing.crm.finance.external.ExternalInvoiceKind
import pl.detailing.crm.finance.external.ExternalInvoiceRequestService
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import pl.detailing.crm.ksef.revenue.issue.IssueRevenueInvoiceHandler
import pl.detailing.crm.shared.FinancialDocumentId
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
import java.util.Optional

/**
 * Wydanie pojazdu w trybie „Faktury wystawia księgowość".
 *
 * Zgłoszenie biznesu: faktura wybrana przy wydaniu, niewysłana do KSeF, a potem wystawiona
 * przez księgowość dawała w CRM tę samą sprzedaż dwa razy. W tym trybie CRM nie tworzy
 * faktury: jest zapis płatności poza przychodem i zgłoszenie dla księgowości.
 */
class CompleteVisitExternalInvoiceTest {

    private val studioId = StudioId.random()
    private val userId = UserId.random()

    private val visitRepository: VisitRepository = mockk()
    private val customerRepository: CustomerRepository = mockk { every { findByIdAndStudioId(any(), any()) } returns null }
    private val created = mutableListOf<CreateFinancialDocumentCommand>()
    private val createFinancialDocumentHandler: CreateFinancialDocumentHandler = mockk {
        every { handle(any()) } answers {
            val cmd = firstArg<CreateFinancialDocumentCommand>()
            created += cmd
            FinancialDocument(
                id = FinancialDocumentId(java.util.UUID.randomUUID()), studioId = studioId, source = cmd.source,
                visitId = cmd.visitId, vehicleBrand = null, vehicleModel = null, customerFirstName = null,
                customerLastName = null, documentNumber = "FAK/2026/0001", documentType = cmd.documentType,
                direction = cmd.direction, status = cmd.paymentMethod.defaultStatus(), paymentMethod = cmd.paymentMethod,
                totalNet = cmd.totalNet, totalVat = cmd.totalVat, totalGross = cmd.totalGross, currency = "PLN",
                issueDate = cmd.issueDate, dueDate = cmd.dueDate, paidAt = null, description = cmd.description,
                counterpartyName = cmd.counterpartyName, counterpartyNip = cmd.counterpartyNip,
                createdBy = userId, updatedBy = userId, createdAt = Instant.now(), updatedAt = Instant.now(),
                invoicedExternally = cmd.invoicedExternally
            )
        }
    }
    private val issueInvoiceHandler: IssueRevenueInvoiceHandler = mockk()
    private val invoiceRepository: KsefRevenueInvoiceRepository = mockk(relaxed = true)
    private var external = true
    private val settingsRepository: StudioSettingsRepository = mockk {
        // Bez nazwy i NIP-u studia: faktura od księgowości ich w CRM nie potrzebuje.
        every { findById(any()) } answers {
            Optional.of(StudioSettingsEntity(studioId = studioId.value).also { it.invoicesIssuedExternally = external })
        }
    }
    private val capabilityService: CapabilityService = mockk(relaxed = true) {
        every { hasCapability(any(), any()) } returns true
    }
    private val externalInvoices: ExternalInvoiceRequestService = mockk(relaxed = true)
    private val transactions = RecordingTransactionManager()

    private val handler = CompleteVisitHandler(
        visitRepository, customerRepository, mockk(relaxed = true), createFinancialDocumentHandler,
        capabilityService, mockk(relaxed = true), mockk<FinancialDocumentRepository>(relaxed = true),
        transactions.template(), settingsRepository, externalInvoices
    )
    private val orchestrator = CompleteVisitInvoiceOrchestrator(
        handler, issueInvoiceHandler, createFinancialDocumentHandler, mockk(relaxed = true),
        settingsRepository, visitRepository, customerRepository, capabilityService,
        auditService = mockk(relaxed = true), invoiceRepository = invoiceRepository
    )

    /** Wizyta za 123,00 zł brutto (100,00 netto). */
    private fun givenVisit(status: VisitStatus = VisitStatus.READY_FOR_PICKUP): VisitId {
        val visit = VisitFixtures.visit(studioId = studioId, status = status)
        every { visitRepository.lockForUpdate(visit.id.value, studioId.value) } returns visit.id.value
        every { visitRepository.findByIdAndStudioIdWithPhotos(visit.id.value, studioId.value) } answers {
            VisitEntity.fromDomain(visit)
        }
        every { visitRepository.save(any()) } answers { firstArg() }
        return visit.id
    }

    private fun command(visitId: VisitId, method: PaymentMethod = PaymentMethod.CARD) = CompleteVisitCommand(
        studioId = studioId, userId = userId, visitId = visitId, paymentMethod = method,
        documentType = DocumentType.INVOICE, dueDate = if (method == PaymentMethod.TRANSFER) java.time.LocalDate.now().plusDays(7) else null
    )

    @Test
    fun `faktura przy wydaniu - bez faktury w CRM, zapis platnosci poza przychodem i zgloszenie dla ksiegowosci`() = runBlocking {
        val visitId = givenVisit()
        val buyer = slot<ExternalInvoiceBuyer>()
        every { externalInvoices.open(any(), ExternalInvoiceKind.INVOICE, capture(buyer), any(), any(), any()) } returns mockk()

        val result = orchestrator.handle(
            command(visitId, PaymentMethod.TRANSFER),
            CompleteInvoiceDetails(
                // Pozycje z formularza są pomijane - całą kwotę obejmuje faktura księgowości.
                items = listOf(CompleteInvoiceItem(name = "Mycie", unitPriceNet = 5_000)),
                buyer = CompleteInvoiceBuyer(nip = "526-104-08-28", name = "Auto Serwis sp. z o.o.", addressLine1 = "Polna 1"),
                remainderPaymentMethod = PaymentMethod.CASH
            )
        )

        assertTrue(result.invoicedExternally)
        assertNull(result.ksefInvoice)
        assertNull(result.remainderDocumentNumber)
        verify(exactly = 0) { issueInvoiceHandler.handle(any()) }
        verify(exactly = 0) { issueInvoiceHandler.validate(any()) }

        val document = created.single()
        assertEquals(DocumentType.INVOICE, document.documentType)
        assertTrue(document.invoicedExternally, "dokument poza sumami przychodu")
        assertEquals(12_300, document.totalGross, "cała kwota wizyty, nie pozycje z formularza")
        assertEquals(10_000, document.totalNet)
        assertEquals(PaymentMethod.TRANSFER, document.paymentMethod)
        assertEquals("5261040828", document.counterpartyNip)
        assertEquals("5261040828", buyer.captured.normalizedNip)
        assertEquals("Polna 1", buyer.captured.addressLine1)
    }

    @Test
    fun `wydanie bez szczegolow faktury (starszy klient) tez nie liczy faktury do przychodu`() = runBlocking {
        val visitId = givenVisit()
        every { externalInvoices.open(any(), any(), any(), any(), any(), any()) } returns mockk()

        val result = handler.handle(command(visitId))

        assertTrue(result.invoicedExternally)
        assertTrue(created.single().invoicedExternally)
        verify(exactly = 1) { externalInvoices.open(any(), ExternalInvoiceKind.INVOICE, any(), any(), any(), any()) }
    }

    @Test
    fun `paragon w trybie ksiegowosci liczy sie normalnie`() = runBlocking {
        val visitId = givenVisit()

        handler.handle(command(visitId).copy(documentType = DocumentType.RECEIPT, paymentMethod = PaymentMethod.CASH))

        assertFalse(created.single().invoicedExternally)
        verify(exactly = 0) { externalInvoices.open(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `tryb wylaczony - faktura jak dotad, bez zgloszenia dla ksiegowosci`() = runBlocking {
        external = false
        val visitId = givenVisit()

        handler.handle(command(visitId))

        assertFalse(created.single().invoicedExternally)
        verify(exactly = 0) { externalInvoices.open(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `powtorzone wydanie niczego nie wystawia`() = runBlocking {
        val visitId = givenVisit(VisitStatus.COMPLETED)

        val result = orchestrator.handle(
            command(visitId),
            CompleteInvoiceDetails(items = emptyList(), buyer = CompleteInvoiceBuyer(name = "Jan Kowalski"))
        )

        assertTrue(result.completion.alreadyInTargetState)
        assertTrue(created.isEmpty())
        verify(exactly = 0) { externalInvoices.open(any(), any(), any(), any(), any(), any()) }
    }
}
