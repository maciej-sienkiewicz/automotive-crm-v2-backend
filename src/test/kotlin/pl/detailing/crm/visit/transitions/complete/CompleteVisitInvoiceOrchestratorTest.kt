package pl.detailing.crm.visit.transitions.complete

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.finance.document.CreateFinancialDocumentHandler
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.ksef.domain.PaymentForm
import pl.detailing.crm.ksef.revenue.issue.IssueRevenueInvoiceHandler
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

class CompleteVisitInvoiceOrchestratorTest {

    private val completeVisitHandler = mockk<CompleteVisitHandler>()
    private val issueInvoiceHandler = mockk<IssueRevenueInvoiceHandler>()
    private val createFinancialDocumentHandler = mockk<CreateFinancialDocumentHandler>()
    private val settingsRepository = mockk<StudioSettingsRepository>()
    private val visitRepository = mockk<VisitRepository>()
    private val customerRepository = mockk<CustomerRepository>()

    private val orchestrator = CompleteVisitInvoiceOrchestrator(
        completeVisitHandler, issueInvoiceHandler, createFinancialDocumentHandler,
        settingsRepository, visitRepository, customerRepository
    )

    private val studioId = StudioId(UUID.randomUUID())

    private fun command() = CompleteVisitCommand(
        studioId = studioId,
        userId = UserId(UUID.randomUUID()),
        visitId = VisitId(UUID.randomUUID()),
        paymentMethod = PaymentMethod.CARD
    )

    private fun invoiceDetails(items: List<CompleteInvoiceItem>) = CompleteInvoiceDetails(items = items)

    // ── Kwoty ──────────────────────────────────────────────────────────────────

    @Test
    fun `liczy sumy faktury per stawka z zaokragleniem`() {
        val totals = orchestrator.computeInvoiceTotals(
            listOf(
                CompleteInvoiceItem(name = "Korekta lakieru", unitPriceNet = 100_000, vatRate = "23"),
                CompleteInvoiceItem(name = "Ozonowanie", quantity = BigDecimal("2"), unitPriceNet = 5_000, vatRate = "8")
            )
        )
        assertEquals(110_000, totals.net)         // 1000 + 2×50
        assertEquals(23_800, totals.vat)          // 23 000 + 800
        assertEquals(133_800, totals.gross)
    }

    @Test
    fun `pozycja wpisana brutto zachowuje dokladna kwote brutto`() {
        // 500,00 zł brutto — kwota wpisana nie może „przeskoczyć" na 499,99
        val totals = orchestrator.computeInvoiceTotals(
            listOf(CompleteInvoiceItem(name = "Detailing", unitPriceGross = 50_000, vatRate = "23"))
        )
        assertEquals(50_000, totals.gross)
        assertEquals(9_350, totals.vat)      // 50000×23/123 (w stu)
        assertEquals(40_650, totals.net)
        assertEquals(totals.gross, totals.net + totals.vat)
    }

    @Test
    fun `pozycja z podwojna cena lub bez ceny jest odrzucana`() {
        assertThrows<ValidationException> {
            runBlocking {
                orchestrator.handle(
                    command(),
                    invoiceDetails(listOf(
                        CompleteInvoiceItem(name = "X", unitPriceNet = 100, unitPriceGross = 123)
                    ))
                )
            }
        }
        assertThrows<ValidationException> {
            runBlocking {
                orchestrator.handle(command(), invoiceDetails(listOf(CompleteInvoiceItem(name = "X"))))
            }
        }
    }

    @Test
    fun `mapuje metody platnosci wizyty na formy FA3`() {
        assertEquals(PaymentForm.GOTOWKA, orchestrator.toPaymentForm(PaymentMethod.CASH))
        assertEquals(PaymentForm.KARTA, orchestrator.toPaymentForm(PaymentMethod.CARD))
        assertEquals(PaymentForm.PRZELEW, orchestrator.toPaymentForm(PaymentMethod.TRANSFER))
        assertEquals(PaymentForm.MOBILNA, orchestrator.toPaymentForm(PaymentMethod.BLIK_NA_NUMER))
        assertEquals(PaymentForm.MOBILNA, orchestrator.toPaymentForm(PaymentMethod.BLIK_TERMINAL))
        assertNull(orchestrator.toPaymentForm(PaymentMethod.OTHER))
    }

    // ── Pre-walidacja (nic nie może się zmienić przed nią) ─────────────────────

    @Test
    fun `odrzuca fakture bez pozycji i z ujemnymi kwotami`() {
        val noItems = assertThrows<ValidationException> {
            runBlocking { orchestrator.handle(command(), invoiceDetails(emptyList())) }
        }
        assertTrue(noItems.message!!.contains("pozycję"))

        assertThrows<ValidationException> {
            runBlocking {
                orchestrator.handle(
                    command(),
                    invoiceDetails(listOf(CompleteInvoiceItem(name = "Usługa", unitPriceNet = -100)))
                )
            }
        }
    }

    @Test
    fun `blokuje wystawienie gdy brak danych firmy — zanim wizyta zostanie zakonczona`() {
        every { settingsRepository.findById(studioId.value) } returns Optional.of(
            StudioSettingsEntity(studioId = studioId.value, name = null, taxId = null)
        )

        val error = assertThrows<ValidationException> {
            runBlocking {
                orchestrator.handle(
                    command(),
                    invoiceDetails(listOf(CompleteInvoiceItem(name = "Usługa", unitPriceNet = 100_000)))
                )
            }
        }
        assertTrue(error.message!!.contains("dane firmy", ignoreCase = true) ||
            error.message!!.contains("danych firmy", ignoreCase = true))
    }
}
