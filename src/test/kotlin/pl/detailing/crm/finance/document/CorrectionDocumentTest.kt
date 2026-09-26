package pl.detailing.crm.finance.document

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
import pl.detailing.crm.finance.infrastructure.FinancialDocumentNumberSequenceRepository
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.finance.reporting.PaymentMethodReportHandler
import pl.detailing.crm.finance.reporting.PaymentMethodReportQuery
import pl.detailing.crm.finance.reporting.ReportGranularity
import pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus
import pl.detailing.crm.ksef.revenue.domain.RevenueInvoiceType
import pl.detailing.crm.ksef.revenue.domain.RevenueSource
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceEntity
import pl.detailing.crm.livemetrics.BusinessEventPublisher
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Dokument korekty (storno) — fundament poprawki rozliczenia i odbicia faktur
 * korygujących KSeF w raporcie według formy płatności.
 */
class CorrectionDocumentTest {

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val today = LocalDate.of(2026, 9, 26)

    private val saved = mutableListOf<FinancialDocumentEntity>()
    private val documents: FinancialDocumentRepository = mockk {
        every { save(any()) } answers { firstArg<FinancialDocumentEntity>().also { saved += it } }
    }
    private val register = CashRegisterEntity(studioId = studioId.value, balance = 61_500)
    private val cashOps = mutableListOf<CashOperationEntity>()
    private val cashRegisters: CashRegisterRepository = mockk {
        every { findByStudioIdForUpdate(studioId.value) } returns register
        every { save(any()) } answers { firstArg() }
    }
    private val cashOperations: CashOperationRepository = mockk {
        every { save(any()) } answers { firstArg<CashOperationEntity>().also { cashOps += it } }
    }
    private val sequences: FinancialDocumentNumberSequenceRepository = mockk {
        every { maxIssuedSequence(any(), any()) } returns 0
        every { nextValue(any(), any(), any(), any()) } returns 1
    }
    private val metrics: BusinessEventPublisher = mockk(relaxed = true)

    private val handler = CreateFinancialDocumentHandler(
        documents, cashRegisters, cashOperations, sequences, mockk(relaxed = true), mockk(relaxed = true), metrics
    )

    private fun storno(gross: Long = -61_500, net: Long = -50_000, correctsId: UUID? = UUID.randomUUID()) =
        CreateFinancialDocumentCommand(
            studioId = studioId, userId = userId, userDisplayName = "Anna", visitId = null,
            documentType = DocumentType.CORRECTION, direction = DocumentDirection.INCOME,
            paymentMethod = PaymentMethod.CASH, totalNet = net, totalVat = gross - net, totalGross = gross,
            issueDate = today, dueDate = today, description = "Korekta PAR/2026/0001",
            counterpartyName = null, counterpartyNip = null, correctsDocumentId = correctsId
        )

    @Test
    fun `storno paragonu gotowkowego wyjmuje gotowke z kasy jako korekta dokumentu`() {
        val document = handler.handle(storno())

        assertEquals("KOR/2026/0001", document.documentNumber)
        assertEquals(-61_500, document.totalGross)
        assertEquals(0L, register.balance)
        assertEquals(CashOperationType.DOCUMENT_CORRECTION, cashOps.single().operationType)
        assertEquals(-61_500, cashOps.single().amount)
        verify(exactly = 0) { metrics.publish(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `korekta bez dokumentu korygowanego albo z mieszanymi znakami jest odrzucana`() {
        assertThrows<ValidationException> { handler.handle(storno(correctsId = null)) }
        assertThrows<ValidationException> { handler.handle(storno(gross = 1_000, net = -500)) }
    }

    @Test
    fun `zwykly dokument nadal nie przyjmuje ujemnych kwot`() {
        assertThrows<ValidationException> {
            handler.handle(storno().copy(documentType = DocumentType.RECEIPT, correctsDocumentId = null))
        }
    }

    @Test
    fun `reczna korekta KSeF do zera trafia do dokumentow z forma platnosci faktury`() {
        val original = financeDoc(DocumentType.INVOICE, PaymentMethod.CARD, gross = 123_000, ksefId = UUID.randomUUID())
        every { documents.findActiveByKsefInvoice(studioId.value, original.ksefRevenueInvoiceId!!) } returns listOf(original)
        val kor = KsefRevenueInvoiceEntity(
            studioId = studioId.value, source = RevenueSource.CRM, ksefStatus = KsefRevenueStatus.PENDING,
            invoiceNumber = "FK/2026/0001", invoiceType = RevenueInvoiceType.KOR,
            originalInvoiceId = original.ksefRevenueInvoiceId, issueDate = today,
            totalNet = -100_000, totalVat = -23_000, totalGross = -123_000
        )

        KsefCorrectionFinanceMirror(documents, handler).mirror(kor, userId, "Anna")

        val mirrored = saved.last()
        assertEquals(DocumentType.CORRECTION, mirrored.documentType)
        assertEquals(PaymentMethod.CARD, mirrored.paymentMethod)
        assertEquals(-123_000, mirrored.totalGross)
        assertEquals(original.id, mirrored.correctsDocumentId)
        assertEquals(kor.id, mirrored.ksefRevenueInvoiceId, "powiązana z korektą — kafle KSeF nie policzą jej drugi raz")
    }

    @Test
    fun `raport form platnosci - paragon i jego storno daja zero i nie licza sie jako dokumenty`() {
        val receipt = financeDoc(DocumentType.RECEIPT, PaymentMethod.CASH, gross = 61_500).apply { supersededAt = Instant.now() }
        val correction = financeDoc(DocumentType.CORRECTION, PaymentMethod.CASH, gross = -61_500)
        val replacement = financeDoc(DocumentType.RECEIPT, PaymentMethod.CARD, gross = 50_000)
        val reportRepo: FinancialDocumentRepository = mockk {
            every { findPaidIncomeForReport(any(), any(), any(), any()) } returns listOf(receipt, correction, replacement)
        }

        val report = PaymentMethodReportHandler(reportRepo).getReport(
            PaymentMethodReportQuery(studioId, ReportGranularity.MONTHLY, today.withDayOfMonth(1), today, null)
        )

        assertEquals(0L, report.cash.totalGross)
        assertEquals(0, report.cash.count)
        assertEquals(50_000L, report.card.totalGross)
        assertEquals(1, report.card.count)
    }

    private fun financeDoc(type: DocumentType, method: PaymentMethod, gross: Long, ksefId: UUID? = null) =
        FinancialDocumentEntity(
            id = UUID.randomUUID(), studioId = studioId.value, source = DocumentSource.VISIT, visitId = UUID.randomUUID(),
            vehicleBrand = null, vehicleModel = null, customerFirstName = null, customerLastName = null,
            documentNumber = "${type.prefix}/2026/0001", documentType = type, direction = DocumentDirection.INCOME,
            status = DocumentStatus.PAID, paymentMethod = method, totalNet = gross, totalVat = 0, totalGross = gross,
            issueDate = today, dueDate = today, paidAt = Instant.now(), description = null,
            counterpartyName = null, counterpartyNip = null, createdBy = userId.value, updatedBy = userId.value,
            ksefRevenueInvoiceId = ksefId
        )
}
