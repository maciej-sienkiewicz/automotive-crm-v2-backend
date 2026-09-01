package pl.detailing.crm.finance.reporting

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceRepository
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import pl.detailing.crm.shared.StudioId
import java.util.UUID

/**
 * Kafle podsumowania liczą z tego samego zbioru dokumentów, co lista przychodów:
 * moduł finansowy (paragony, dokumenty „inne") plus ledger KSeF (faktury i korekty).
 * Wcześniej przychód brał wyłącznie moduł finansowy, więc korekta w KSeF nie
 * zmieniała kafla „Przychody", a faktura wystawiona poza wizytą nie liczyła się wcale.
 */
class FinanceReportingHandlerTest {

    private val documentRepository = mockk<FinancialDocumentRepository>()
    private val ksefInvoiceRepository = mockk<KsefInvoiceRepository>()
    private val revenueInvoiceRepository = mockk<KsefRevenueInvoiceRepository>()

    private val handler = FinanceReportingHandler(
        documentRepository, ksefInvoiceRepository, revenueInvoiceRepository
    )

    private val studioId = StudioId(UUID.randomUUID())

    private fun stub(
        docsIncomePaid: Long = 0,
        docsIncomePending: Long = 0,
        docsExpensePaid: Long = 0,
        docsExpensePending: Long = 0,
        revenuePaid: Long = 0,
        revenuePending: Long = 0,
        ksefCostsPaid: Long = 0,
        ksefCostsPending: Long = 0
    ) {
        // Handler pyta o listy statusów: PAID jako „rozliczone", PENDING+OVERDUE jako
        // „nierozliczone" — zaległy dokument nie znika z należności.
        val settled = listOf(DocumentStatus.PAID)
        val outstanding = listOf(DocumentStatus.PENDING, DocumentStatus.OVERDUE)
        every {
            documentRepository.sumGross(any(), DocumentDirection.INCOME, settled, any(), any())
        } returns docsIncomePaid
        every {
            documentRepository.sumGross(any(), DocumentDirection.INCOME, outstanding, any(), any())
        } returns docsIncomePending
        every {
            documentRepository.sumGross(any(), DocumentDirection.EXPENSE, settled, any(), any())
        } returns docsExpensePaid
        every {
            documentRepository.sumGross(any(), DocumentDirection.EXPENSE, outstanding, any(), any())
        } returns docsExpensePending
        every { revenueInvoiceRepository.sumGrossByPaymentStatus(any(), "PAID", any(), any()) } returns revenuePaid
        every { revenueInvoiceRepository.sumGrossByPaymentStatus(any(), "PENDING", any(), any()) } returns revenuePending
        every { ksefInvoiceRepository.sumGrossByPaymentStatus(any(), "PAID", any(), any()) } returns ksefCostsPaid
        every { ksefInvoiceRepository.sumGrossByPaymentStatus(any(), "PENDING", any(), any()) } returns ksefCostsPending
        every { documentRepository.countOverdue(any(), any()) } returns 0
    }

    private fun summary() = handler.getSummary(FinanceReportQuery(studioId = studioId))

    @Test
    fun `przychod sumuje paragony z modulu finansowego i faktury z ledgera KSeF`() {
        stub(docsIncomePaid = 20_000, revenuePaid = 100_000)
        assertEquals(120_000, summary().totalRevenue)
    }

    @Test
    fun `korekta do zera zdejmuje fakture z przychodu`() {
        // Ledger zwraca sumę ze znakiem: faktura +1000 zł, korekta −1000 zł
        stub(docsIncomePaid = 20_000, revenuePaid = 100_000 - 100_000)
        assertEquals(20_000, summary().totalRevenue)   // zostaje sam paragon
    }

    @Test
    fun `korekta faktury nieoplaconej pomniejsza naleznosci`() {
        stub(docsIncomePending = 5_000, revenuePending = 100_000 - 100_000)
        assertEquals(5_000, summary().pendingReceivables)
    }

    @Test
    fun `korekta w okresie bez faktur daje ujemny przychod zamiast wyjatku`() {
        // Faktura z poprzedniego roku skorygowana w bieżącym — Money zabraniało
        // kwot ujemnych i wywracało cały endpoint podsumowania
        stub(revenuePaid = -100_000)
        assertEquals(-100_000, summary().totalRevenue)
    }

    @Test
    fun `zysk pokazuje strate wprost, bez przycinania do zera`() {
        stub(revenuePaid = 30_000, docsExpensePaid = 50_000)
        val result = summary()
        assertEquals(30_000, result.totalRevenue)
        assertEquals(50_000, result.totalCosts)
        assertEquals(-20_000, result.profit)
    }

    @Test
    fun `koszty nadal sumuja modul finansowy i faktury kosztowe KSeF`() {
        stub(docsExpensePaid = 10_000, ksefCostsPaid = 25_000)   // ledger zwraca grosze
        assertEquals(35_000, summary().totalCosts)
    }
}
