package pl.detailing.crm.visit.settlement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus
import pl.detailing.crm.ksef.revenue.domain.RevenueInvoiceType
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VatRate
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitStatus
import java.time.Instant
import java.time.LocalDate

/**
 * Kiedy poprawki NIE da się zrobić (i co wtedy widzi użytkownik) oraz co mówi podgląd.
 * Każdy przypadek to coś, co biznes może wyklikać w oknie „Popraw rozliczenie".
 */
class SettlementPlanningTest {

    private val h = SettlementHarness()

    private fun blockReason(cmd: SettlementCorrectionCommand) = h.preview(cmd).blockReason

    // ── Stan wizyty ────────────────────────────────────────────────────────────

    @Test
    fun `wizyta niewydana, zarchiwizowana albo odrzucona - odmowa`() {
        h.cashReceipt()
        listOf(VisitStatus.IN_PROGRESS, VisitStatus.READY_FOR_PICKUP, VisitStatus.ARCHIVED, VisitStatus.REJECTED).forEach {
            h.visit = h.visit.copy(status = it)
            assertTrue(blockReason(h.command(prices = h.price(40_000)))!!.contains("wydanej"), "status $it")
        }
    }

    @Test
    fun `wizyta innego studia albo nieistniejaca - nie znaleziono`() {
        assertThrows<EntityNotFoundException> { h.preview(h.command().copy(visitId = VisitId.random())) }
    }

    // ── Nic się nie zmienia ────────────────────────────────────────────────────

    @Test
    fun `ta sama cena, dokument i platnosc - odmowa`() {
        h.cashReceipt()
        assertTrue(blockReason(h.command())!!.startsWith("Nic się nie zmienia"))
    }

    @Test
    fun `cena wpisana ponownie ta sama co byla - to nie jest zmiana`() {
        h.cashReceipt()
        assertTrue(blockReason(h.command(prices = h.price(50_000, 61_500)))!!.startsWith("Nic się nie zmienia"))
    }

    // ── Ceny ───────────────────────────────────────────────────────────────────

    @Test
    fun `ujemna cena albo brutto niezgodne z netto - odmowa z powodem`() {
        h.cashReceipt()
        assertTrue(blockReason(h.command(prices = h.price(-100)))!!.contains("ujemna"))
        assertTrue(blockReason(h.command(prices = h.price(50_000, 70_000)))!!.contains("nie odpowiada"))
    }

    @Test
    fun `pozycja odrzucona nie da sie poprawic`() {
        val rejected = h.serviceItem(10_000, 12_300, status = pl.detailing.crm.shared.VisitServiceStatus.REJECTED)
        h.withItems(h.serviceItem(50_000, 61_500), rejected)
        h.cashReceipt()
        assertTrue(blockReason(h.command(prices = h.price(5_000, id = rejected.id)))!!.contains("nie należy"))
    }

    // ── Płatność ───────────────────────────────────────────────────────────────

    @Test
    fun `przelew bez terminu platnosci - odmowa, z terminem - ok`() {
        h.cashReceipt()
        assertTrue(blockReason(h.command(method = PaymentMethod.TRANSFER))!!.contains("termin"))
        assertNull(blockReason(h.command(method = PaymentMethod.TRANSFER, dueDate = LocalDate.now().plusDays(7))))
    }

    // ── Faktura: warunki ───────────────────────────────────────────────────────

    @Test
    fun `faktura bez modulu Finanse - odmowa`() {
        h.cashReceipt()
        h.hasFinance = false
        assertTrue(blockReason(h.command(type = DocumentType.INVOICE))!!.contains("modułu Finanse"))
    }

    @Test
    fun `faktura bez danych firmy w ustawieniach - odmowa`() {
        h.cashReceipt()
        h.companyComplete = false
        assertTrue(blockReason(h.command(type = DocumentType.INVOICE))!!.contains("dane firmy"))
    }

    @Test
    fun `faktura bez nabywcy - odmowa`() {
        h.cashReceipt()
        assertTrue(blockReason(h.command(type = DocumentType.INVOICE, buyer = SettlementBuyer()))!!.contains("nabywcy"))
    }

    @Test
    fun `faktura z NIP-em o zlej dlugosci - odmowa z walidacji faktury`() {
        h.cashReceipt()
        val reason = blockReason(h.command(type = DocumentType.INVOICE, buyer = SettlementBuyer(nip = "123456789", name = "Firma")))
        assertTrue(reason!!.contains("10 cyfr"))
    }

    @Test
    fun `faktura ze stawka zw bez podstawy zwolnienia - odmowa, z podstawa - ok`() {
        h.cashReceipt()
        val zw = h.price(50_000, 50_000, VatRate.VAT_ZW)
        assertTrue(blockReason(h.command(type = DocumentType.INVOICE, prices = zw))!!.contains("podstawy prawnej"))
        assertNull(blockReason(h.command(type = DocumentType.INVOICE, prices = zw, exemption = "art. 113 ust. 1")))
    }

    @Test
    fun `nabywca z kartoteki klienta, gdy formularz go nie podaje`() {
        h.cashReceipt()
        h.customer = customer(companyName = "Auto-Komis sp. z o.o.", companyNip = "5261040828")
        assertNull(blockReason(h.command(type = DocumentType.INVOICE, buyer = null)))
    }

    // ── Faktura KSeF: stany ────────────────────────────────────────────────────

    @Test
    fun `faktura wysylana albo w sesji KSeF - czekamy na odpowiedz`() {
        listOf(KsefRevenueStatus.SENDING, KsefRevenueStatus.SUBMITTED).forEach { status ->
            val h = SettlementHarness()
            h.invoiceWithDocument(status)
            val reason = h.preview(h.command(type = DocumentType.INVOICE, method = PaymentMethod.CARD, prices = h.price(40_000))).blockReason
            assertTrue(reason!!.contains("czeka na odpowiedź KSeF"), "status $status")
        }
    }

    @Test
    fun `faktura przyjeta, ktora ma juz korekte - kolejna korekta w module KSeF`() {
        h.invoiceWithDocument(KsefRevenueStatus.ACCEPTED)
        h.existingCorrection = true
        val reason = blockReason(h.command(type = DocumentType.INVOICE, method = PaymentMethod.CARD, prices = h.price(40_000)))
        assertTrue(reason!!.contains("ma już korektę"))
    }

    @Test
    fun `faktura odrzucona, anulowana albo wyzerowana korekta nie obowiazuje`() {
        val rejected = h.invoice(KsefRevenueStatus.REJECTED, number = "FV/2026/0001")
        val cancelled = h.invoice(KsefRevenueStatus.CANCELLED, number = "FV/2026/0002")
        val zeroed = h.invoice(KsefRevenueStatus.ACCEPTED, number = "FV/2026/0003")
        h.invoice(KsefRevenueStatus.ACCEPTED, gross = -61_500, number = "FK/2026/0001", type = RevenueInvoiceType.KOR, originalId = zeroed.id)
        h.cashReceipt()

        val state = h.service.loadState(h.studioId.value, h.visit.id.value)

        assertTrue(state.activeInvoices.none { it.id in setOf(rejected.id, cancelled.id, zeroed.id) })
    }

    @Test
    fun `faktura z czesciowa korekta dalej obowiazuje`() {
        val partly = h.invoice(KsefRevenueStatus.ACCEPTED)
        h.invoice(KsefRevenueStatus.ACCEPTED, gross = -10_000, number = "FK/2026/0001", type = RevenueInvoiceType.KOR, originalId = partly.id)
        assertEquals(listOf(partly.id), h.service.loadState(h.studioId.value, h.visit.id.value).activeInvoices.map { it.id })
    }

    // ── Stan dokumentów ────────────────────────────────────────────────────────

    @Test
    fun `dokumenty usuniete, zastapione, korekty i koszty nie sa obecnym rozliczeniem`() {
        h.cashReceipt().apply { deletedAt = Instant.now() }
        h.cashReceipt().apply { supersededAt = Instant.now() }
        h.document(DocumentType.CORRECTION, PaymentMethod.CASH, gross = -61_500, net = -50_000)
        h.document(DocumentType.OTHER, PaymentMethod.CASH, direction = DocumentDirection.EXPENSE)
        val current = h.document(DocumentType.RECEIPT, PaymentMethod.CARD)

        val state = h.service.loadState(h.studioId.value, h.visit.id.value)

        assertEquals(listOf(current.id), state.activeDocuments.map { it.id })
        assertEquals(PaymentMethod.CARD, state.paymentMethod)
        assertEquals(DocumentType.RECEIPT, state.documentType)
    }

    @Test
    fun `faktura plus paragon reszty - rodzaj rozliczenia to faktura`() {
        val (inv, _) = h.invoiceWithDocument(KsefRevenueStatus.ACCEPTED)
        h.document(DocumentType.RECEIPT, PaymentMethod.CASH, gross = 10_000, net = 8_130)
        val state = h.service.loadState(h.studioId.value, h.visit.id.value)
        assertEquals(DocumentType.INVOICE, state.documentType)
        assertEquals(listOf(inv.id), state.activeInvoices.map { it.id })
    }

    @Test
    fun `do paragonu jest juz faktura z tymi samymi danymi - odmowa`() {
        h.cashReceipt()
        h.invoice(KsefRevenueStatus.ACCEPTED, toReceipt = true)
        assertTrue(blockReason(h.command(type = DocumentType.INVOICE))!!.contains("jest już faktura"))
    }

    // ── Podgląd ────────────────────────────────────────────────────────────────

    @Test
    fun `podglad niczego nie zapisuje`() {
        h.cashReceipt()
        h.preview(h.command(prices = h.price(40_650, 50_000)))
        assertTrue(h.created.isEmpty())
        assertTrue(h.savedVisits.isEmpty())
        assertTrue(h.savedCorrections.isEmpty())
        assertEquals(0, h.transactions.begun)
    }

    @Test
    fun `podglad liczy doplate, zwrot i kase`() {
        h.cashReceipt()
        val cheaper = h.preview(h.command(prices = h.price(40_650, 50_000)))
        assertEquals(61_500, cheaper.totalGrossBefore)
        assertEquals(50_000, cheaper.totalGrossAfter)
        assertEquals(-11_500, cheaper.customerDifference)
        assertEquals(-11_500, cheaper.cashDelta)

        val pricierByCard = h.preview(h.command(method = PaymentMethod.CARD, prices = h.price(60_000)))
        assertEquals(73_800 - 61_500, pricierByCard.customerDifference)
        assertEquals(-61_500, pricierByCard.cashDelta, "gotówka wychodzi z kasy, nowa płatność kartą")
    }

    @Test
    fun `podglad nie uzywa kropki srodkowej w zdaniach`() {
        h.cashReceipt()
        val steps = h.preview(h.command(method = PaymentMethod.CARD, prices = h.price(40_000))).steps
        assertTrue(steps.isNotEmpty())
        assertTrue(steps.none { it.contains('·') || it.contains('•') })
    }

    @Test
    fun `wykonanie odmawia z tym samym powodem co podglad i nic nie zapisuje`() {
        h.cashReceipt()
        val ex = assertThrows<ValidationException> { h.execute(h.command()) }
        assertTrue(ex.message!!.startsWith("Nic się nie zmienia"))
        assertEquals(1, h.transactions.rollbacks)
        assertTrue(h.created.isEmpty())
        assertTrue(h.audits.isEmpty())
    }

    private fun customer(companyName: String, companyNip: String) =
        io.mockk.mockk<pl.detailing.crm.customer.infrastructure.CustomerEntity>(relaxed = true) {
            io.mockk.every { this@mockk.companyName } returns companyName
            io.mockk.every { this@mockk.companyNip } returns companyNip
            io.mockk.every { firstName } returns "Jan"
            io.mockk.every { lastName } returns "Kowalski"
            io.mockk.every { email } returns null
        }
}
