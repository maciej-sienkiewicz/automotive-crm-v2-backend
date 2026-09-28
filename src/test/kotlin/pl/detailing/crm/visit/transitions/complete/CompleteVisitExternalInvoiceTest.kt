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
import org.junit.jupiter.api.assertThrows
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
    private var customer: pl.detailing.crm.customer.infrastructure.CustomerEntity? = null
    private val customerRepository: CustomerRepository = mockk(relaxed = true) {
        every { findByIdAndStudioId(any(), any()) } answers { customer }
    }
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
    /** Kolejne odczyty flagi - do odtworzenia przestawienia jej w trakcie wydania. */
    private val externalReads = ArrayDeque<Boolean>()
    private var companyComplete = false
    private val settingsRepository: StudioSettingsRepository = mockk {
        // Domyślnie bez nazwy i NIP-u studia: faktura od księgowości ich w CRM nie potrzebuje.
        every { findById(any()) } answers {
            Optional.of(
                StudioSettingsEntity(
                    studioId = studioId.value,
                    name = if (companyComplete) "Studio" else null,
                    taxId = if (companyComplete) "5271234567" else null
                ).also { it.invoicesIssuedExternally = externalReads.removeFirstOrNull() ?: external }
            )
        }
    }
    private var financeModule = true
    private val capabilityService: CapabilityService = mockk(relaxed = true) {
        every { hasCapability(any(), any()) } answers { financeModule }
    }
    private val documentRepository: FinancialDocumentRepository = mockk(relaxed = true)
    private val externalInvoices: ExternalInvoiceRequestService = mockk(relaxed = true)
    private val transactions = RecordingTransactionManager()

    private val handler = CompleteVisitHandler(
        visitRepository, customerRepository, mockk(relaxed = true), createFinancialDocumentHandler,
        capabilityService, mockk(relaxed = true), documentRepository,
        transactions.template(), settingsRepository, externalInvoices
    )
    private val orchestrator = CompleteVisitInvoiceOrchestrator(
        handler, issueInvoiceHandler, createFinancialDocumentHandler, mockk(relaxed = true),
        settingsRepository, visitRepository, customerRepository, capabilityService,
        auditService = mockk(relaxed = true), invoiceRepository = invoiceRepository
    )

    /** Wizyta za 123,00 zł brutto (100,00 netto). */
    private fun givenVisit(
        status: VisitStatus = VisitStatus.READY_FOR_PICKUP,
        items: List<pl.detailing.crm.visit.domain.VisitServiceItem> = listOf(VisitFixtures.serviceItem())
    ): VisitId {
        val visit = VisitFixtures.visit(studioId = studioId, status = status, items = items)
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

    // ── Przypadki brzegowe ────────────────────────────────────────────────────

    @Test
    fun `cena wpisana jako brutto 1900,00 - zapis i zgloszenie dokladnie na 1900,00, VAT to roznica`() = runBlocking {
        val typed = VisitFixtures.serviceItem(finalPriceNet = 154_472, finalPriceGross = 190_000)
            .copy(basePriceGross = pl.detailing.crm.shared.Money(190_000))
        val visitId = givenVisit(items = listOf(typed))

        handler.handle(command(visitId))

        val document = created.single()
        assertEquals(190_000, document.totalGross, "nie 190001")
        assertEquals(154_472, document.totalNet)
        assertEquals(35_528, document.totalVat)
    }

    @Test
    fun `gotowka - zapis platnosci idzie jako gotowka, wiec wplyw do kasy jak przy paragonie`() = runBlocking {
        val visitId = givenVisit()

        handler.handle(command(visitId, PaymentMethod.CASH))

        val document = created.single()
        assertEquals(PaymentMethod.CASH, document.paymentMethod)
        assertTrue(document.invoicedExternally)
    }

    @Test
    fun `wizyta za darmo - nie ma dokumentu ani zgloszenia dla ksiegowosci`() = runBlocking {
        val visitId = givenVisit(items = listOf(VisitFixtures.serviceItem(finalPriceNet = 0, finalPriceGross = 0)))

        val result = handler.handle(command(visitId))

        assertTrue(created.isEmpty())
        assertFalse(result.invoicedExternally)
        verify(exactly = 0) { externalInvoices.open(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `studio bez modulu Finanse - wizyta wydana bez dokumentu i bez zgloszenia`() = runBlocking {
        financeModule = false
        val visitId = givenVisit()

        handler.handle(command(visitId))

        assertTrue(created.isEmpty())
        verify(exactly = 0) { externalInvoices.open(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `bez danych nabywcy z formularza - nabywca z kartoteki, firma z adresem`() = runBlocking {
        customer = mockk(relaxed = true) {
            every { companyNip } returns "526-104-08-28"
            every { companyName } returns "Auto Serwis sp. z o.o."
            every { companyAddressStreet } returns "Polna 1"
            every { companyAddressPostalCode } returns "00-001"
            every { companyAddressCity } returns "Warszawa"
            every { email } returns "faktury@autoserwis.pl"
        }
        val buyer = slot<ExternalInvoiceBuyer>()
        every { externalInvoices.open(any(), any(), capture(buyer), any(), any(), any()) } returns mockk()
        val visitId = givenVisit()

        handler.handle(command(visitId))

        assertEquals("5261040828", buyer.captured.normalizedNip)
        assertEquals("Auto Serwis sp. z o.o.", buyer.captured.name)
        assertEquals("Polna 1", buyer.captured.addressLine1)
        assertEquals("00-001 Warszawa", buyer.captured.addressLine2)
        assertEquals("Auto Serwis sp. z o.o.", created.single().counterpartyName)
    }

    @Test
    fun `brak jakiegokolwiek nabywcy - odmowa zanim wizyta zostanie wydana`() {
        val visitId = givenVisit()

        assertThrows<pl.detailing.crm.shared.ValidationException> {
            runBlocking { orchestrator.handle(command(visitId), CompleteInvoiceDetails(items = emptyList())) }
        }
        verify(exactly = 0) { visitRepository.save(any()) }
        assertTrue(created.isEmpty())
    }

    @Test
    fun `przelew bez terminu - odmowa zanim wizyta zostanie wydana`() {
        val visitId = givenVisit()

        assertThrows<pl.detailing.crm.shared.ValidationException> {
            runBlocking {
                orchestrator.handle(
                    command(visitId, PaymentMethod.TRANSFER).copy(dueDate = null),
                    CompleteInvoiceDetails(items = emptyList(), buyer = CompleteInvoiceBuyer(name = "Jan Kowalski"))
                )
            }
        }
        verify(exactly = 0) { visitRepository.save(any()) }
    }

    @Test
    fun `tryb wlaczony w trakcie wydania - CRM nie wystawia faktury obok zapisu dla ksiegowosci`() = runBlocking {
        // Orkiestrator czyta flagę jako wyłączoną, a transakcja wydania - już jako włączoną.
        companyComplete = true
        externalReads += listOf(false, true)
        every { issueInvoiceHandler.validate(any()) } returns Unit
        val visitId = givenVisit()

        val result = orchestrator.handle(
            command(visitId),
            CompleteInvoiceDetails(
                items = listOf(CompleteInvoiceItem(name = "Mycie", unitPriceNet = 10_000)),
                buyer = CompleteInvoiceBuyer(name = "Jan Kowalski")
            )
        )

        assertTrue(result.invoicedExternally)
        assertNull(result.ksefInvoice)
        verify(exactly = 0) { issueInvoiceHandler.handle(any()) }
        assertTrue(created.single().invoicedExternally)
    }

    @Test
    fun `powtorka wydania mowi, ze fakture wystawia ksiegowosc - jak przy pierwszym wydaniu`() = runBlocking {
        val visitId = givenVisit(VisitStatus.COMPLETED)
        val placeholder = mockk<pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity>(relaxed = true) {
            every { id } returns java.util.UUID.randomUUID()
            every { documentNumber } returns "FAK/2026/0001"
            every { invoicedExternally } returns true
        }
        every { documentRepository.findAllByVisitIdAndStudioIdAndDeletedAtIsNull(visitId.value, studioId.value) } returns listOf(placeholder)

        val result = orchestrator.handle(
            command(visitId),
            CompleteInvoiceDetails(items = emptyList(), buyer = CompleteInvoiceBuyer(name = "Jan Kowalski"))
        )

        assertTrue(result.completion.alreadyInTargetState)
        assertTrue(result.invoicedExternally)
        assertTrue(created.isEmpty())
    }

    @Test
    fun `statystyki - wizyta z faktura ksiegowosci jest wydana jak kazda inna, z pelna kwota`() = runBlocking {
        val saved = mutableListOf<VisitEntity>()
        val visitId = givenVisit()
        every { visitRepository.save(capture(saved)) } answers { firstArg() }

        handler.handle(command(visitId))

        val completed = saved.single().toDomain()
        assertEquals(VisitStatus.COMPLETED, completed.status)
        assertEquals(12_300, completed.calculateTotalGross().amountInCents, "statystyki liczą z wizyty, nie z dokumentów")
    }
}
