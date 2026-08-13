package pl.detailing.crm.ksef.revenue.issue

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus
import pl.detailing.crm.ksef.revenue.domain.RevenueInvoiceType
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceItemRepository
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import pl.detailing.crm.ksef.revenue.send.KsefRevenueDispatchService
import pl.detailing.crm.ksef.revenue.xml.Fa3XmlBuilder
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

class IssueRevenueInvoiceHandlerTest {

    private val invoiceRepository = mockk<KsefRevenueInvoiceRepository>()
    private val itemRepository = mockk<KsefRevenueInvoiceItemRepository>(relaxed = true)
    private val settingsRepository = mockk<StudioSettingsRepository>()
    private val dispatchService = mockk<KsefRevenueDispatchService>()

    private val handler = IssueRevenueInvoiceHandler(
        invoiceRepository, itemRepository, settingsRepository, Fa3XmlBuilder(), dispatchService
    )

    private val studioId = StudioId(UUID.randomUUID())
    private val userId = UserId(UUID.randomUUID())

    private fun settings() = StudioSettingsEntity(
        studioId = studioId.value,
        name = "Studio Detailingu Blask",
        taxId = "123-456-32-18",
        street = "ul. Polerska 7",
        postalCode = "00-001",
        city = "Warszawa",
        bankAccount = "PL61109010140000071219812874"
    )

    private fun command(
        items: List<RevenueInvoiceItemCommand> = listOf(
            RevenueInvoiceItemCommand(name = "Korekta lakieru", unitPriceNet = 100_000, vatRate = "23")
        ),
        buyer: RevenueInvoiceBuyerCommand = RevenueInvoiceBuyerCommand(
            nip = "5213017228", name = "Firma Testowa"
        ),
        paymentForm: String? = "GOTOWKA",
        isPaid: Boolean = true,
        exemptionLegalBasis: String? = null
    ) = IssueRevenueInvoiceCommand(
        studioId = studioId,
        userId = userId,
        buyer = buyer,
        items = items,
        issueDate = LocalDate.of(2026, 8, 13),
        paymentForm = paymentForm,
        isPaid = isPaid,
        exemptionLegalBasis = exemptionLegalBasis
    )

    private fun stubHappyPath(existingCount: Long = 0) {
        every { settingsRepository.findById(studioId.value) } returns Optional.of(settings())
        every {
            invoiceRepository.countCrmInvoicesInYear(studioId.value, RevenueInvoiceType.VAT, any(), any())
        } returns existingCount
        every { invoiceRepository.save(any()) } answers { firstArg() }
    }

    @Test
    fun `wystawia fakture z numeracja FV-rok-seq i policzonymi kwotami`() {
        stubHappyPath(existingCount = 4)

        val invoice = handler.persistInvoice(
            command(
                items = listOf(
                    RevenueInvoiceItemCommand(
                        name = "Powłoka ceramiczna", quantity = BigDecimal("2"),
                        unitPriceNet = 150_000, vatRate = "23"
                    ),
                    RevenueInvoiceItemCommand(name = "Odgrzybianie klimatyzacji", unitPriceNet = 10_000, vatRate = "8")
                )
            )
        )

        assertEquals("FV/2026/0005", invoice.invoiceNumber)
        assertEquals(310_000, invoice.totalNet)          // 2×1500 + 100 netto
        assertEquals(69_800, invoice.totalVat)           // 69 000 (23%) + 800 (8%)
        assertEquals(379_800, invoice.totalGross)
        assertEquals(KsefRevenueStatus.PENDING, invoice.ksefStatus)
        assertEquals("1234563218", invoice.sellerNip)    // NIP znormalizowany z myślników
        assertEquals("PAID", invoice.paymentStatus)
        assertNotNull(invoice.invoiceXml)
        assertNotNull(invoice.paidAt)
    }

    @Test
    fun `faktura B2C bez NIP wymaga nazwy nabywcy`() {
        stubHappyPath()
        assertThrows<ValidationException> {
            handler.persistInvoice(command(buyer = RevenueInvoiceBuyerCommand(nip = null, name = null)))
        }
        val invoice = handler.persistInvoice(
            command(buyer = RevenueInvoiceBuyerCommand(nip = null, name = "Jan Kowalski"))
        )
        assertNull(invoice.buyerNip)
        assertEquals("Jan Kowalski", invoice.buyerName)
    }

    @Test
    fun `walidacja odrzuca brak pozycji, zly NIP i zw bez podstawy`() {
        stubHappyPath()
        assertThrows<ValidationException> { handler.persistInvoice(command(items = emptyList())) }
        assertThrows<ValidationException> {
            handler.persistInvoice(command(buyer = RevenueInvoiceBuyerCommand(nip = "123", name = "X")))
        }
        assertThrows<ValidationException> {
            handler.persistInvoice(
                command(
                    items = listOf(RevenueInvoiceItemCommand(name = "Usługa zw", unitPriceNet = 1000, vatRate = "zw")),
                    exemptionLegalBasis = null
                )
            )
        }
        assertThrows<ValidationException> {
            handler.persistInvoice(command(paymentForm = "BITCOIN"))
        }
    }

    @Test
    fun `przelew bez terminu platnosci jest odrzucany`() {
        stubHappyPath()
        assertThrows<ValidationException> {
            handler.persistInvoice(command(paymentForm = "PRZELEW", isPaid = false))
        }
    }

    @Test
    fun `brak danych firmy blokuje wystawienie z czytelnym komunikatem`() {
        every { settingsRepository.findById(studioId.value) } returns Optional.of(
            StudioSettingsEntity(studioId = studioId.value, name = null, taxId = null)
        )
        val error = assertThrows<ValidationException> { handler.persistInvoice(command()) }
        assertEquals(true, error.message!!.contains("dane firmy"))
    }
}
