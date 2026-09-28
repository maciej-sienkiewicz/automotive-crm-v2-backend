package pl.detailing.crm.finance.external

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.domain.PageImpl
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.finance.document.UpdateFinancialDocumentCommand
import pl.detailing.crm.finance.document.UpdateFinancialDocumentHandler
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentSource
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.visit.domain.VisitFixtures
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visitcard.visitCardPaymentStatus
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/**
 * Przypadki brzegowe trybu „Faktury wystawia księgowość" poza wydaniem i poprawką:
 * ręczna edycja zapisu płatności w Finansach, karta wizyty dla klienta, usunięta wizyta
 * na liście „Do zafakturowania" i oddzielenie studiów.
 */
class ExternalInvoicingEdgeCasesTest {

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val documents = mutableMapOf<UUID, FinancialDocumentEntity>()
    private val documentRepository: FinancialDocumentRepository = mockk {
        every { findByIdAndStudioId(any(), any()) } answers { documents[firstArg()] }
        every { save(any()) } answers { firstArg() }
        every { findAllById(any()) } answers { firstArg<Iterable<UUID>>().mapNotNull { documents[it] } }
    }

    private fun doc(
        type: DocumentType = DocumentType.INVOICE,
        method: PaymentMethod = PaymentMethod.TRANSFER,
        status: DocumentStatus = method.defaultStatus(),
        external: Boolean = true,
        superseded: Boolean = false,
        gross: Long = 61_500,
        visitId: UUID = UUID.randomUUID()
    ) = FinancialDocumentEntity(
        id = UUID.randomUUID(), studioId = studioId.value, source = DocumentSource.VISIT, visitId = visitId,
        vehicleBrand = null, vehicleModel = null, customerFirstName = null, customerLastName = null,
        documentNumber = "${type.prefix}/2026/0001", documentType = type, direction = DocumentDirection.INCOME,
        status = status, paymentMethod = method, totalNet = gross * 100 / 123, totalVat = gross - gross * 100 / 123,
        totalGross = gross, issueDate = LocalDate.now(), dueDate = LocalDate.now().plusDays(7), paidAt = null,
        description = null, counterpartyName = "Jan Kowalski", counterpartyNip = null,
        createdBy = userId.value, updatedBy = userId.value,
        supersededAt = if (superseded) Instant.now() else null,
        correctsDocumentId = if (type == DocumentType.CORRECTION) UUID.randomUUID() else null,
        invoicedExternally = external
    ).also { documents[it.id] = it }

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    // ── Ręczna edycja zapisu płatności w Finansach ────────────────────────────

    private val update = UpdateFinancialDocumentHandler(documentRepository, mockk(relaxed = true), mockk(relaxed = true))

    private fun updateCmd(d: FinancialDocumentEntity, method: PaymentMethod = d.paymentMethod, buyer: String? = d.counterpartyName) =
        UpdateFinancialDocumentCommand(
            studioId, userId, "Anna", d.id, d.documentType, method, d.totalNet, d.totalVat, d.totalGross,
            d.issueDate, d.dueDate, "opis", buyer, d.counterpartyNip
        )

    @Test
    fun `nabywcy faktury ksiegowosci nie zmienia sie w Finansach, bo ksiegowosc by o tym nie wiedziala`() {
        val placeholder = doc()

        val error = assertThrows<ValidationException> { update.handle(updateCmd(placeholder, buyer = "Anna Nowak")) }

        assertTrue(error.message!!.contains("Popraw rozliczenie"))
        assertEquals("Jan Kowalski", placeholder.counterpartyName)
    }

    @Test
    fun `forme platnosci i opis zapisu platnosci da sie zmienic jak w kazdym dokumencie`() {
        val placeholder = doc(method = PaymentMethod.TRANSFER)

        update.handle(updateCmd(placeholder, method = PaymentMethod.CARD))

        assertEquals(PaymentMethod.CARD, placeholder.paymentMethod)
        assertEquals(DocumentStatus.PAID, placeholder.status)
        assertTrue(placeholder.invoicedExternally, "edycja nie wciąga sprzedaży z powrotem do przychodu")
    }

    // ── Karta wizyty dla klienta ─────────────────────────────────────────────

    @Test
    fun `karta wizyty - po zmianie przelewu na karte klient widzi oplacone, a nie czeka na przelew`() {
        val oldTransfer = doc(method = PaymentMethod.TRANSFER, superseded = true)
        val storno = doc(type = DocumentType.CORRECTION, method = PaymentMethod.TRANSFER, status = DocumentStatus.PENDING, gross = -61_500)
        val newCard = doc(method = PaymentMethod.CARD)

        assertEquals("PAID", visitCardPaymentStatus(listOf(oldTransfer, storno, newCard)))
    }

    @Test
    fun `karta wizyty - nieoplacony przelew za fakture ksiegowosci to czeka na platnosc`() {
        assertEquals("PENDING", visitCardPaymentStatus(listOf(doc(method = PaymentMethod.TRANSFER))))
        assertEquals("OVERDUE", visitCardPaymentStatus(listOf(doc(status = DocumentStatus.OVERDUE))))
        assertEquals(null, visitCardPaymentStatus(emptyList()))
    }

    // ── Usunięta wizyta na liście „Do zafakturowania" ─────────────────────────

    private fun request(document: FinancialDocumentEntity, owner: StudioId = studioId) = ExternalInvoiceRequestEntity(
        studioId = owner.value, visitId = document.visitId, financialDocumentId = document.id,
        kind = ExternalInvoiceKind.INVOICE, buyerNip = null, buyerName = "Jan Kowalski", buyerAddressLine1 = null,
        buyerAddressLine2 = null, buyerEmail = null, totalNet = document.totalNet, totalVat = document.totalVat,
        totalGross = document.totalGross, createdBy = userId.value
    )

    @Test
    fun `usunieta wizyta - sprzedaz zostaje na liscie z informacja, ze wizyty juz nie ma`() {
        val deletedVisit = VisitEntity.fromDomain(VisitFixtures.visit(studioId = studioId)).also { it.deletedAt = Instant.now() }
        val liveVisit = VisitEntity.fromDomain(VisitFixtures.visit(studioId = studioId))
        val onDeleted = request(doc(visitId = deletedVisit.id))
        val onLive = request(doc(visitId = liveVisit.id))
        val repository: ExternalInvoiceRequestRepository = mockk {
            every { findPage(any(), any(), any()) } returns PageImpl(listOf(onDeleted, onLive))
            every { findAllById(any()) } returns emptyList()
        }
        val visits: VisitRepository = mockk { every { findAllById(any()) } returns listOf(deletedVisit, liveVisit) }
        SecurityContextHolder.setContext(SecurityContextImpl(
            UserPrincipal(userId = userId, studioId = studioId, isOwner = true, email = "o@studio.pl",
                fullName = "Anna Kowalska", phoneNumber = "+48600000000")
        ))

        val items = ExternalInvoicesController(repository, mockk(), documentRepository, visits)
            .list("PENDING", 1, 20).body!!.items

        assertTrue(items.single { it.id == onDeleted.id.toString() }.visitDeleted)
        assertFalse(items.single { it.id == onLive.id.toString() }.visitDeleted)
        assertEquals(deletedVisit.visitNumber, items.single { it.id == onDeleted.id.toString() }.visitNumber)
    }

    // ── Oddzielenie studiów ──────────────────────────────────────────────────

    @Test
    fun `zgloszenia innego studia nie da sie odhaczyc ani cofnac`() {
        val foreign = request(doc(), owner = StudioId.random())
        val repository: ExternalInvoiceRequestRepository = mockk {
            every { findByIdAndStudioId(any(), any()) } answers {
                foreign.takeIf { it.id == firstArg<UUID>() && it.studioId == secondArg<UUID>() }
            }
        }
        val service = ExternalInvoiceRequestService(repository, mockk(relaxed = true))

        assertThrows<NotFoundException> { service.markIssued(studioId, foreign.id, "FV 1", userId, "Anna") }
        assertThrows<NotFoundException> { service.unmarkIssued(studioId, foreign.id, userId, "Anna") }
        assertEquals(ExternalInvoiceStatus.PENDING, foreign.status)
    }
}
