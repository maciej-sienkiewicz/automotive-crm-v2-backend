package pl.detailing.crm.finance.external

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.duplicates.DocumentDuplicateLinkRepository
import pl.detailing.crm.finance.income.IncomeDocumentFilters
import pl.detailing.crm.finance.income.IncomeDocumentsRepository
import pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus
import pl.detailing.crm.ksef.revenue.domain.RevenueInvoiceType
import pl.detailing.crm.ksef.revenue.domain.RevenueSource
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceEntity
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * „Faktury wystawia księgowość" przeciwko prawdziwemu Postgresowi: zapis płatności
 * (invoiced_externally) nie wchodzi do kafli ani do listy przychodów, automat duplikatów
 * go nie rusza, a raport form płatności go liczy. Faktura księgowości z KSeF niesie przychód.
 *
 * Oznaczony `@Tag("testcontainers")` jak pozostałe testy bazodanowe — uruchamia się
 * przez `./gradlew test -PrunTestcontainers`.
 */
@Tag("testcontainers")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(IncomeDocumentsRepository::class)
@Testcontainers
class InvoicedExternallyPersistenceTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired lateinit var documents: FinancialDocumentRepository
    @Autowired lateinit var invoices: KsefRevenueInvoiceRepository
    @Autowired lateinit var income: IncomeDocumentsRepository
    @Autowired lateinit var duplicates: DocumentDuplicateLinkRepository
    @Autowired lateinit var requests: ExternalInvoiceRequestRepository

    private val studioId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val today = LocalDate.now()

    private fun document(
        type: DocumentType,
        net: Long,
        gross: Long,
        external: Boolean,
        status: DocumentStatus = DocumentStatus.PAID,
        method: PaymentMethod = PaymentMethod.CARD,
        corrects: UUID? = null
    ) = documents.save(
        FinancialDocumentEntity(
            id = UUID.randomUUID(), studioId = studioId, visitId = UUID.randomUUID(),
            vehicleBrand = null, vehicleModel = null, customerFirstName = null, customerLastName = null,
            documentNumber = "${type.prefix}/${UUID.randomUUID()}", documentType = type,
            direction = DocumentDirection.INCOME, status = status, paymentMethod = method,
            totalNet = net, totalVat = gross - net, totalGross = gross, issueDate = today,
            dueDate = today.minusDays(1), paidAt = Instant.now(), description = null,
            counterpartyName = "Auto Serwis", counterpartyNip = null, createdBy = userId, updatedBy = userId,
            correctsDocumentId = corrects, invoicedExternally = external
        )
    )

    private fun accountantInvoice(net: Long, gross: Long) = invoices.save(
        KsefRevenueInvoiceEntity(
            studioId = studioId, source = RevenueSource.EXTERNAL, ksefStatus = KsefRevenueStatus.ACCEPTED,
            invoiceNumber = "FV 12/09/2026", ksefNumber = "5261040828-20260928-ABC", invoiceType = RevenueInvoiceType.VAT,
            issueDate = today, buyerName = "Auto Serwis", totalNet = net, totalVat = gross - net, totalGross = gross
        ).also { it.paymentStatus = "PAID" }
    )

    @Test
    fun `kafle - przychod z paragonu i z faktury ksiegowosci, bez zapisu platnosci`() {
        document(DocumentType.RECEIPT, 50_000, 61_500, external = false)
        val placeholder = document(DocumentType.INVOICE, 154_472, 190_000, external = true)
        document(DocumentType.CORRECTION, -154_472, -190_000, external = true, corrects = placeholder.id)
        document(DocumentType.INVOICE, 154_472, 190_000, external = true)
        accountantInvoice(154_472, 190_000)

        val crm = documents.sumNet(studioId, DocumentDirection.INCOME, listOf(DocumentStatus.PAID), null, null)
        val ksef = invoices.sumNetByPaymentStatus(studioId, "PAID", null, null)

        assertEquals(50_000, crm, "zapisy płatności i ich storna poza sumą")
        assertEquals(204_472, crm + ksef, "paragon + faktura księgowości, sprzedaż raz")
    }

    @Test
    fun `przeterminowane - zapis platnosci przelewem nie trafia do kafla`() {
        document(DocumentType.INVOICE, 10_000, 12_300, external = true, status = DocumentStatus.OVERDUE, method = PaymentMethod.TRANSFER)
        document(DocumentType.RECEIPT, 10_000, 12_300, external = false, status = DocumentStatus.OVERDUE, method = PaymentMethod.TRANSFER)

        assertEquals(1, documents.countOverdue(studioId, DocumentDirection.INCOME))
    }

    @Test
    fun `lista przychodow - faktura ksiegowosci jest, zapisu platnosci nie ma`() {
        val receipt = document(DocumentType.RECEIPT, 50_000, 61_500, external = false)
        document(DocumentType.INVOICE, 154_472, 190_000, external = true)
        val invoice = accountantInvoice(154_472, 190_000)

        val rows = income.findPage(IncomeDocumentFilters(studioId = studioId), limit = 50, offset = 0)

        assertEquals(setOf(receipt.id.toString(), invoice.id.toString()), rows.map { it.id }.toSet())
    }

    @Test
    fun `automat duplikatow nie paruje zapisu platnosci z faktura ksiegowosci`() {
        document(DocumentType.INVOICE, 154_472, 190_000, external = true)
        val invoice = accountantInvoice(154_472, 190_000)
        val candidatesBefore = duplicates.findRevenueVsFinancialCandidates(studioId, today.minusDays(45), today, 3)
        assertTrue(candidatesBefore.isEmpty())

        val receipt = document(DocumentType.RECEIPT, 154_472, 190_000, external = false)
        val candidates = duplicates.findRevenueVsFinancialCandidates(studioId, today.minusDays(45), today, 3)
        assertEquals(listOf(invoice.id to receipt.id), candidates.map { UUID.fromString(it[0].toString()) to UUID.fromString(it[1].toString()) })
    }

    @Test
    fun `raport form platnosci liczy zapis platnosci`() {
        val placeholder = document(DocumentType.INVOICE, 154_472, 190_000, external = true)

        val rows = documents.findPaidIncomeForReport(studioId, null, null, null)

        assertEquals(listOf(placeholder.id), rows.map { it.id })
    }

    @Test
    fun `zgloszenia - czekajace, wystawione, wycofane`() {
        val doc = document(DocumentType.INVOICE, 154_472, 190_000, external = true)
        fun request(status: ExternalInvoiceStatus) = requests.save(
            ExternalInvoiceRequestEntity(
                studioId = studioId, visitId = doc.visitId, financialDocumentId = doc.id, kind = ExternalInvoiceKind.INVOICE,
                status = status, buyerNip = null, buyerName = "Auto Serwis", buyerAddressLine1 = null,
                buyerAddressLine2 = null, buyerEmail = null, totalNet = 154_472, totalVat = 35_528,
                totalGross = 190_000, createdBy = userId
            )
        )
        val pending = request(ExternalInvoiceStatus.PENDING)
        val issued = request(ExternalInvoiceStatus.ISSUED)
        val withdrawn = request(ExternalInvoiceStatus.WITHDRAWN)

        assertEquals(setOf(pending.id, issued.id), requests.findActiveByDocuments(studioId, listOf(doc.id)).map { it.id }.toSet())
        assertEquals(listOf(withdrawn.id), requests.findWithdrawnByDocument(studioId, doc.id).map { it.id })
        assertEquals(1, requests.countByStudioIdAndStatus(studioId, ExternalInvoiceStatus.PENDING))
        val page = requests.findPage(
            studioId, listOf(ExternalInvoiceStatus.PENDING, ExternalInvoiceStatus.ISSUED), PageRequest.of(0, 20)
        )
        assertEquals(2, page.totalElements)
    }
}
