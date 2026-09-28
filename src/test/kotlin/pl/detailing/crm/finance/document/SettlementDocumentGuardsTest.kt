package pl.detailing.crm.finance.document

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Storno i dokument zastąpiony to para sumująca się do zera. Biznes może kliknąć je
 * w Finansach („Edytuj", „Usuń") — to musi zostać odrzucone z wyjaśnieniem, inaczej
 * rozjedzie się kasa, raport form płatności i historia wizyty.
 */
class SettlementDocumentGuardsTest {

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val documents = mutableMapOf<UUID, FinancialDocumentEntity>()
    private val repository: FinancialDocumentRepository = mockk {
        every { findByIdAndStudioId(any(), any()) } answers { documents[firstArg()] }
        every { save(any()) } answers { firstArg() }
    }

    private fun doc(type: DocumentType, superseded: Boolean = false, gross: Long = 61_500) = FinancialDocumentEntity(
        id = UUID.randomUUID(), studioId = studioId.value, source = DocumentSource.VISIT, visitId = UUID.randomUUID(),
        vehicleBrand = null, vehicleModel = null, customerFirstName = null, customerLastName = null,
        documentNumber = "${type.prefix}/2026/0001", documentType = type, direction = DocumentDirection.INCOME,
        status = DocumentStatus.PAID, paymentMethod = PaymentMethod.CASH, totalNet = gross, totalVat = 0, totalGross = gross,
        issueDate = LocalDate.now(), dueDate = null, paidAt = Instant.now(), description = null,
        counterpartyName = null, counterpartyNip = null, createdBy = userId.value, updatedBy = userId.value,
        supersededAt = if (superseded) Instant.now() else null,
        correctsDocumentId = if (type == DocumentType.CORRECTION) UUID.randomUUID() else null
    ).also { documents[it.id] = it }

    private val removal = FinancialDocumentRemovalHandler(
        repository, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
    )
    private val update = UpdateFinancialDocumentHandler(repository, mockk(relaxed = true), mockk(relaxed = true))

    private fun removeCmd(d: FinancialDocumentEntity) = RemoveFinancialDocumentCommand(studioId, userId, "Anna", d.id)
    private fun updateCmd(d: FinancialDocumentEntity) = UpdateFinancialDocumentCommand(
        studioId, userId, "Anna", d.id, d.documentType, PaymentMethod.CARD, d.totalNet, d.totalVat, d.totalGross,
        d.issueDate, null, "zmiana", null, null
    )

    @Test
    fun `korekty nie da sie usunac ani edytowac`() {
        val storno = doc(DocumentType.CORRECTION, gross = -61_500)
        assertTrue(assertThrows<ValidationException> { removal.delete(removeCmd(storno)) }.message!!.contains("korekta"))
        assertThrows<ValidationException> { update.handle(updateCmd(storno)) }
        assertTrue(storno.deletedAt == null)
    }

    @Test
    fun `dokumentu zastapionego nie da sie usunac ani edytowac`() {
        val old = doc(DocumentType.RECEIPT, superseded = true)
        assertTrue(assertThrows<ValidationException> { removal.delete(removeCmd(old)) }.message!!.contains("zastąpiony"))
        assertThrows<ValidationException> { update.handle(updateCmd(old)) }
    }

    @Test
    fun `zwykly obowiazujacy paragon nadal da sie edytowac`() {
        val receipt = doc(DocumentType.RECEIPT)
        update.handle(updateCmd(receipt))
        assertTrue(receipt.paymentMethod == PaymentMethod.CARD)
    }

    // ── Odbicie ręcznej korekty KSeF ──────────────────────────────────────────

    private val createHandler: CreateFinancialDocumentHandler = mockk(relaxed = true)
    private val mirror = KsefCorrectionFinanceMirror(repository, createHandler)

    private fun ksef(type: RevenueInvoiceType, originalId: UUID? = null) = KsefRevenueInvoiceEntity(
        studioId = studioId.value, source = RevenueSource.CRM, ksefStatus = KsefRevenueStatus.PENDING,
        invoiceNumber = "FK/2026/0001", invoiceType = type, originalInvoiceId = originalId, issueDate = LocalDate.now(),
        totalNet = -100, totalVat = -23, totalGross = -123
    )

    @Test
    fun `odbicie - zwykla faktura albo korekta faktury spoza wizyty nie tworzy dokumentu`() {
        every { repository.findActiveByKsefInvoice(any(), any()) } returns emptyList()
        mirror.mirror(ksef(RevenueInvoiceType.VAT), userId, "Anna")
        mirror.mirror(ksef(RevenueInvoiceType.KOR, originalId = null), userId, "Anna")
        mirror.mirror(ksef(RevenueInvoiceType.KOR, originalId = UUID.randomUUID()), userId, "Anna")
        verify(exactly = 0) { createHandler.handle(any()) }
    }

    @Test
    fun `odbicie - korekta dodatnia (doplata) tez trafia do dokumentow ze znakiem plus`() {
        val invoiceId = UUID.randomUUID()
        val original = doc(DocumentType.INVOICE).also { it.ksefRevenueInvoiceId = invoiceId }
        every { repository.findActiveByKsefInvoice(studioId.value, invoiceId) } returns listOf(original)
        val plus = ksef(RevenueInvoiceType.KOR, invoiceId).let {
            KsefRevenueInvoiceEntity(
                studioId = it.studioId, source = it.source, ksefStatus = it.ksefStatus, invoiceNumber = it.invoiceNumber,
                invoiceType = it.invoiceType, originalInvoiceId = invoiceId, issueDate = it.issueDate,
                totalNet = 1_000, totalVat = 230, totalGross = 1_230
            )
        }

        mirror.mirror(plus, userId, "Anna")

        verify { createHandler.handle(match { it.documentType == DocumentType.CORRECTION && it.totalGross == 1_230L }) }
    }
}
