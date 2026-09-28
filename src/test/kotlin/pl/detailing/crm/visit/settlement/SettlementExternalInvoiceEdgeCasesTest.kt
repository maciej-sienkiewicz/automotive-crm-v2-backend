package pl.detailing.crm.visit.settlement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.external.ExternalInvoiceKind
import pl.detailing.crm.finance.external.ExternalInvoiceStatus

/**
 * Przypadki brzegowe poprawki rozliczenia w trybie „Faktury wystawia księgowość":
 * kolejne poprawki jedna po drugiej, usługa za darmo, zmiana samego nabywcy, gotówka,
 * wyłączenie trybu przy fakturze już wystawionej przez księgowość.
 *
 * Każdy test sprawdza trzy rzeczy naraz: co dostaje księgowość (zgłoszenia), co liczy
 * się do przychodu w Finansach ([SettlementHarness.financeRevenueNet]) i co dzieje się
 * z kasą. Rozjazd którejkolwiek z nich to ta sama sprzedaż policzona dwa razy albo wcale.
 */
class SettlementExternalInvoiceEdgeCasesTest {

    private val h = SettlementHarness().apply { invoicesExternal = true }

    private fun active(kind: ExternalInvoiceKind) =
        h.requests.filter { it.kind == kind && it.status != ExternalInvoiceStatus.WITHDRAWN }

    @Test
    fun `zmiana ceny przy wystawionej fakturze ksiegowosci - korekta i nowa faktura do wystawienia`() {
        val (_, issued) = h.externalInvoice(PaymentMethod.CARD, issuedNumber = "FV 12/09/2026")

        h.execute(h.command(DocumentType.INVOICE, PaymentMethod.CARD, prices = h.price(40_000)))

        val correction = active(ExternalInvoiceKind.CORRECTION).single()
        assertEquals(issued.id, correction.correctsRequestId)
        assertEquals(-61_500, correction.totalGross)
        val newInvoice = active(ExternalInvoiceKind.INVOICE).single { it.status == ExternalInvoiceStatus.PENDING }
        assertEquals(49_200, newInvoice.totalGross)
        assertTrue(h.issuedInvoices.isEmpty() && h.issuedCorrections.isEmpty(), "KSeF z CRM nie rusza")
        assertEquals(0, h.financeRevenueNet)
    }

    @Test
    fun `druga poprawka pod rzad - czekajaca faktura wycofana, korekta z pierwszej zostaje jedna`() {
        h.externalInvoice(PaymentMethod.CARD, issuedNumber = "FV 12/09/2026")
        h.execute(h.command(DocumentType.INVOICE, PaymentMethod.CARD, prices = h.price(40_000)))
        val firstPending = active(ExternalInvoiceKind.INVOICE).single { it.status == ExternalInvoiceStatus.PENDING }

        h.execute(h.command(DocumentType.INVOICE, PaymentMethod.CARD, prices = h.price(30_000)))

        assertEquals(ExternalInvoiceStatus.WITHDRAWN, firstPending.status, "nie wystawiona - korekty nie trzeba")
        assertEquals(1, h.requests.count { it.kind == ExternalInvoiceKind.CORRECTION }, "korekta tylko do wystawionej")
        assertEquals(36_900, active(ExternalInvoiceKind.INVOICE).single { it.status == ExternalInvoiceStatus.PENDING }.totalGross)
        assertEquals(0, h.financeRevenueNet)
    }

    @Test
    fun `usluga za darmo po fakcie przy wystawionej fakturze - sama korekta, nic nowego do wystawienia`() {
        val (placeholder, _) = h.externalInvoice(PaymentMethod.CARD, issuedNumber = "FV 12/09/2026")

        h.execute(h.command(DocumentType.INVOICE, PaymentMethod.CARD, prices = h.price(0)))

        assertNotNull(placeholder.supersededAt)
        assertEquals(1, active(ExternalInvoiceKind.CORRECTION).size)
        assertTrue(active(ExternalInvoiceKind.INVOICE).none { it.status == ExternalInvoiceStatus.PENDING })
        assertEquals(1, h.documents.count { it.correctsDocumentId == null }, "bez nowego dokumentu na 0 zł")
        assertEquals(0, h.financeRevenueNet)
    }

    @Test
    fun `zmiana samego nabywcy przy czekajacej fakturze - stare zgloszenie wycofane, nowe z nowym nabywca`() {
        val (_, pending) = h.externalInvoice(PaymentMethod.CARD, buyerName = "Jan Kowalski")

        h.execute(h.command(DocumentType.INVOICE, PaymentMethod.CARD, buyer = SettlementBuyer(nip = "5261040828", name = "Auto Serwis")))

        assertEquals(ExternalInvoiceStatus.WITHDRAWN, pending.status)
        val fresh = active(ExternalInvoiceKind.INVOICE).single()
        assertEquals("Auto Serwis", fresh.buyerName)
        assertEquals("5261040828", fresh.buyerNip)
        assertEquals(61_500, fresh.totalGross)
        assertTrue(active(ExternalInvoiceKind.CORRECTION).isEmpty())
    }

    @Test
    fun `ten sam nabywca i nic poza tym - poprawka jest blokowana, a nie tworzy duplikatu`() {
        h.externalInvoice(PaymentMethod.CARD, buyerName = "Jan Kowalski")

        val preview = h.preview(h.command(DocumentType.INVOICE, PaymentMethod.CARD, buyer = SettlementBuyer(name = "Jan Kowalski")))

        assertNotNull(preview.blockReason)
        assertEquals(1, h.requests.size)
    }

    @Test
    fun `gotowka na karte przy fakturze ksiegowosci - kasa oddaje kwote, zgloszenie zostaje`() {
        val (_, request) = h.externalInvoice(PaymentMethod.CASH, issuedNumber = "FV 12/09/2026")
        val cmd = h.command(DocumentType.INVOICE, PaymentMethod.CARD)

        assertEquals(-61_500, h.preview(cmd).cashDelta)
        h.execute(cmd)

        assertEquals(-61_500, h.cashMovement, "storno gotówki, kopia kartą")
        assertEquals(ExternalInvoiceStatus.ISSUED, request.status)
        assertEquals(1, h.requests.size)
        assertEquals(0, h.financeRevenueNet)
    }

    @Test
    fun `faktura ksiegowosci na dokument inny - dokument inny liczy sie do przychodu raz`() {
        val (_, pending) = h.externalInvoice(PaymentMethod.CARD)

        h.execute(h.command(DocumentType.OTHER, PaymentMethod.CARD))

        assertEquals(ExternalInvoiceStatus.WITHDRAWN, pending.status)
        val other = h.documents.single { it.documentType == DocumentType.OTHER }
        assertFalse(other.invoicedExternally)
        assertEquals(50_000, h.financeRevenueNet)
    }

    @Test
    fun `tryb wylaczony przy wystawionej fakturze ksiegowosci - CRM wystawia nowa, ksiegowosc dostaje korekte`() {
        h.invoicesExternal = false
        h.externalInvoice(PaymentMethod.CARD, issuedNumber = "FV 12/09/2026")

        h.execute(h.command(DocumentType.INVOICE, PaymentMethod.CARD, prices = h.price(40_000)))

        assertEquals(1, h.issuedInvoices.size, "nowa faktura z CRM")
        assertEquals(1, active(ExternalInvoiceKind.CORRECTION).size, "starą fakturę koryguje księgowość")
        val newDoc = h.documents.single { it.documentType == DocumentType.INVOICE && it.correctsDocumentId == null && it.supersededAt == null }
        assertFalse(newDoc.invoicedExternally)
        assertNotNull(newDoc.ksefRevenueInvoiceId, "przychód niesie faktura z CRM, nie dokument")
        assertEquals(0, h.financeRevenueNet)
    }

    @Test
    fun `korekta dla ksiegowosci nie blokuje kolejnej poprawki i nie wraca jako obowiazujacy dokument`() {
        h.externalInvoice(PaymentMethod.CARD, issuedNumber = "FV 12/09/2026")
        h.execute(h.command(DocumentType.RECEIPT, PaymentMethod.CARD))

        val state = h.service.loadState(h.studioId.value, h.visit.id.value)

        assertEquals(listOf(DocumentType.RECEIPT), state.activeDocuments.map { it.documentType })
        assertTrue(state.activeRequests.isEmpty(), "korekta wisi na stornie, nie na obowiązującym dokumencie")
        assertNull(h.preview(h.command(DocumentType.INVOICE, PaymentMethod.CARD, prices = h.price(40_000))).blockReason)
    }

    @Test
    fun `statystyki licza sie z wizyty - poprawka ceny zmienia kwote wizyty tak samo jak bez trybu ksiegowosci`() {
        h.externalInvoice(PaymentMethod.CARD)

        h.execute(h.command(DocumentType.INVOICE, PaymentMethod.CARD, prices = h.price(40_000)))

        assertEquals(1, h.savedVisits.size)
        assertEquals(49_200, h.visit.calculateTotalGross().amountInCents)
        assertEquals(40_000, h.visit.calculateTotalNet().amountInCents)
    }
}
