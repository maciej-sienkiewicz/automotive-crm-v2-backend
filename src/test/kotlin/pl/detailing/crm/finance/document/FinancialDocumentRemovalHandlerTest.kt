package pl.detailing.crm.finance.document

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.finance.domain.CashOperationType
import pl.detailing.crm.finance.domain.DocumentDirection
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
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Zgłoszenie: po usunięciu paragonu gotówkowego stan kasy się nie zgadzał — wpłata
 * zostawała w saldzie, a w Aktywności nie było śladu, że ktoś dokument usunął.
 */
class FinancialDocumentRemovalHandlerTest {

    private val studioId = StudioId.random()
    private val userId = UserId.random()

    private val register = CashRegisterEntity(studioId = studioId.value, balance = 0L)
    private val operations = mutableListOf<CashOperationEntity>()
    private val documents = mutableMapOf<UUID, FinancialDocumentEntity>()

    private val documentRepository: FinancialDocumentRepository = mockk {
        every { findByIdAndStudioId(any(), studioId.value) } answers {
            documents[firstArg()]?.takeIf { it.deletedAt == null }
        }
        every { findByIdAndStudioIdIncludingDeleted(any(), studioId.value) } answers { documents[firstArg()] }
        every { save(any()) } answers { firstArg<FinancialDocumentEntity>().also { documents[it.id] = it } }
    }
    private val cashRegisterRepository: CashRegisterRepository = mockk {
        every { findByStudioIdForUpdate(studioId.value) } returns register
        every { save(any()) } answers { firstArg() }
    }
    private val cashOperationRepository: CashOperationRepository = mockk {
        every { findByDocumentId(studioId.value, any()) } answers {
            operations.filter { it.financialDocumentId == secondArg<UUID>() }
        }
        every { save(any()) } answers { firstArg<CashOperationEntity>().also { operations += it } }
    }
    private val auditService: AuditService = mockk(relaxed = true)

    private val handler = FinancialDocumentRemovalHandler(
        documentRepository, cashOperationRepository,
        DocumentCashCorrections(cashRegisterRepository, cashOperationRepository),
        mockk(relaxed = true), auditService
    )

    /** Paragon gotówkowy na 190,00 zł, który wpłynął do kasy (saldo 190,00 zł). */
    private fun cashReceipt(gross: Long = 19_000): FinancialDocumentEntity {
        val document = FinancialDocumentEntity(
            id = UUID.randomUUID(), studioId = studioId.value, visitId = null,
            vehicleBrand = null, vehicleModel = null, customerFirstName = null, customerLastName = null,
            documentNumber = "PAR/2026/0007", documentType = DocumentType.RECEIPT,
            direction = DocumentDirection.INCOME, status = DocumentStatus.PAID, paymentMethod = PaymentMethod.CASH,
            totalNet = 15_447, totalVat = gross - 15_447, totalGross = gross,
            issueDate = LocalDate.now(), dueDate = null, paidAt = Instant.now(), description = null,
            counterpartyName = null, counterpartyNip = null, createdBy = userId.value, updatedBy = userId.value
        )
        documents[document.id] = document
        register.balance += gross
        operations += CashOperationEntity(
            id = UUID.randomUUID(), studioId = studioId.value, cashRegisterId = register.id,
            amount = gross, balanceBefore = register.balance - gross, balanceAfter = register.balance,
            operationType = CashOperationType.PAYMENT_IN, comment = null,
            financialDocumentId = document.id, createdBy = userId.value
        )
        return document
    }

    private fun command(documentId: UUID) = RemoveFinancialDocumentCommand(studioId, userId, "Anna Kowalska", documentId)

    @Test
    fun `usuniecie paragonu gotowkowego cofa wplate w kasie wpisem korygujacym`() {
        val receipt = cashReceipt()

        handler.delete(command(receipt.id))

        assertEquals(0L, register.balance)
        val correction = operations.last()
        assertEquals(CashOperationType.DOCUMENT_CORRECTION, correction.operationType)
        assertEquals(-19_000L, correction.amount)
        assertEquals(receipt.id, correction.financialDocumentId)
        assertEquals(2, operations.size, "wpłata zostaje w historii, dochodzi korekta — nic nie jest kasowane")
        assertNotNull(documents[receipt.id]!!.deletedAt)
    }

    @Test
    fun `usuniecie trafia do Aktywnosci z kwota korekty kasy`() {
        val receipt = cashReceipt()
        val logged = slot<LogAuditCommand>()
        every { auditService.logSync(capture(logged)) } returns Unit

        handler.delete(command(receipt.id))

        assertEquals(AuditAction.DOCUMENT_DELETED, logged.captured.action)
        assertEquals("-19000", logged.captured.metadata["cashCorrection"])
    }

    @Test
    fun `przywrocenie wraca do salda sprzed usuniecia - takze po kilku cyklach`() {
        val receipt = cashReceipt()

        repeat(2) {
            handler.delete(command(receipt.id))
            handler.restore(command(receipt.id))
        }

        assertEquals(19_000L, register.balance)
        assertNull(documents[receipt.id]!!.deletedAt)
        verify(exactly = 2) { auditService.logSync(match { it.action == AuditAction.DOCUMENT_RESTORED }) }
    }

    @Test
    fun `dokument bez ruchu w kasie nie tworzy korekty`() {
        val card = cashReceipt().also { operations.clear(); register.balance = 0L }

        handler.delete(command(card.id))

        assertEquals(0L, register.balance)
        assertEquals(0, operations.size)
    }
}
