package pl.detailing.crm.finance.document

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.finance.domain.CashOperationType
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentSource
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.infrastructure.CashOperationEntity
import pl.detailing.crm.finance.infrastructure.CashOperationRepository
import pl.detailing.crm.finance.infrastructure.CashRegisterEntity
import pl.detailing.crm.finance.infrastructure.CashRegisterRepository
import pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Okno „Edytuj dokument" wołało PUT, którego backend nie miał — każdy zapis się nie
 * udawał. Tu: co wolno zmienić, jak idzie za tym kasa i co trafia do Aktywności.
 */
class UpdateFinancialDocumentHandlerTest {

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val issued = LocalDate.of(2026, 9, 20)

    private val register = CashRegisterEntity(studioId = studioId.value, balance = 50_000L)
    private val operations = mutableListOf<CashOperationEntity>()
    private val documents = mutableMapOf<UUID, FinancialDocumentEntity>()

    private val documentRepository: FinancialDocumentRepository = mockk {
        every { findByIdAndStudioId(any(), studioId.value) } answers { documents[firstArg()] }
        every { save(any()) } answers { firstArg() }
    }
    private val cashRegisterRepository: CashRegisterRepository = mockk {
        every { findByStudioIdForUpdate(studioId.value) } returns register
        every { save(any()) } answers { firstArg() }
    }
    private val cashOperationRepository: CashOperationRepository = mockk {
        every { save(any()) } answers { firstArg<CashOperationEntity>().also { operations += it } }
    }
    private val auditService: AuditService = mockk(relaxed = true)

    private val handler = UpdateFinancialDocumentHandler(
        documentRepository, DocumentCashCorrections(cashRegisterRepository, cashOperationRepository), auditService
    )

    private fun receipt(
        method: PaymentMethod = PaymentMethod.CASH,
        source: DocumentSource = DocumentSource.MANUAL,
        ksefInvoiceId: UUID? = null
    ) = FinancialDocumentEntity(
        id = UUID.randomUUID(), studioId = studioId.value, source = source, visitId = null,
        vehicleBrand = null, vehicleModel = null, customerFirstName = null, customerLastName = null,
        documentNumber = "PAR/2026/0007", documentType = DocumentType.RECEIPT,
        direction = DocumentDirection.INCOME, status = DocumentStatus.PAID, paymentMethod = method,
        totalNet = 15_447, totalVat = 3_553, totalGross = 19_000,
        issueDate = issued, dueDate = issued, paidAt = Instant.now(), description = "Mycie",
        counterpartyName = null, counterpartyNip = null, createdBy = userId.value, updatedBy = userId.value,
        ksefRevenueInvoiceId = ksefInvoiceId
    ).also { documents[it.id] = it }

    private fun command(
        document: FinancialDocumentEntity,
        method: PaymentMethod = document.paymentMethod,
        net: Long = document.totalNet,
        vat: Long = document.totalVat,
        gross: Long = document.totalGross,
        type: DocumentType? = document.documentType,
        description: String? = document.description
    ) = UpdateFinancialDocumentCommand(
        studioId = studioId, userId = userId, userDisplayName = "Anna Kowalska", documentId = document.id,
        documentType = type, paymentMethod = method, totalNet = net, totalVat = vat, totalGross = gross,
        issueDate = document.issueDate, dueDate = document.dueDate, description = description,
        counterpartyName = document.counterpartyName, counterpartyNip = document.counterpartyNip
    )

    @Test
    fun `gotowka na karte - wplata wychodzi z kasy korekta, a zmiana trafia do Aktywnosci`() {
        val document = receipt(PaymentMethod.CASH)
        val logged = slot<LogAuditCommand>()
        every { auditService.logSync(capture(logged)) } returns Unit

        handler.handle(command(document, method = PaymentMethod.CARD))

        assertEquals(31_000L, register.balance)
        assertEquals(CashOperationType.DOCUMENT_CORRECTION, operations.single().operationType)
        assertEquals(-19_000L, operations.single().amount)
        assertEquals(AuditAction.DOCUMENT_UPDATED, logged.captured.action)
        assertTrue(logged.captured.changes.any { it.field == "paymentMethod" && it.newValue == "Karta" })
    }

    @Test
    fun `karta na gotowke - wplata wchodzi do kasy`() {
        val document = receipt(PaymentMethod.CARD)

        handler.handle(command(document, method = PaymentMethod.CASH))

        assertEquals(69_000L, register.balance)
    }

    @Test
    fun `zmiana kwoty paragonu gotowkowego koryguje kase o roznice, a brutto zostaje takie, jak wpisane`() {
        val document = receipt(PaymentMethod.CASH)

        // 1900,00 zł brutto przy 23%: netto 1544,72 — brutto nie może stać się 1900,01
        handler.handle(command(document, net = 154_472, vat = 35_528, gross = 190_000))

        assertEquals(190_000L, documents[document.id]!!.totalGross)
        assertEquals(50_000L + 171_000L, register.balance)
        assertEquals(171_000L, operations.single().amount)
    }

    @Test
    fun `dokument z wydania pojazdu - kwot nie da sie zmienic, forme platnosci tak`() {
        val document = receipt(PaymentMethod.CASH, source = DocumentSource.VISIT)

        assertThrows<ValidationException> { handler.handle(command(document, net = 1, vat = 0, gross = 1)) }
        handler.handle(command(document, method = PaymentMethod.BLIK_TERMINAL))

        assertEquals(PaymentMethod.BLIK_TERMINAL, documents[document.id]!!.paymentMethod)
    }

    @Test
    fun `dokument faktury KSeF - tylko opis`() {
        val document = receipt(PaymentMethod.CARD, ksefInvoiceId = UUID.randomUUID())

        val error = assertThrows<ValidationException> { handler.handle(command(document, method = PaymentMethod.CASH)) }
        assertTrue(error.message!!.contains("korygującą"))

        handler.handle(command(document, description = "Mycie i wosk"))
        assertEquals("Mycie i wosk", documents[document.id]!!.description)
        assertEquals(0, operations.size)
    }

    @Test
    fun `typu dokumentu nie mozna zmienic - numer nalezy do serii`() {
        val document = receipt()

        assertThrows<ValidationException> { handler.handle(command(document, type = DocumentType.OTHER)) }
    }

    @Test
    fun `zapis bez zmian nie tworzy wpisu w Aktywnosci ani w kasie`() {
        val document = receipt()

        handler.handle(command(document))

        assertEquals(0, operations.size)
        verify(exactly = 0) { auditService.logSync(any()) }
        verify(exactly = 0) { documentRepository.save(any()) }
    }
}
