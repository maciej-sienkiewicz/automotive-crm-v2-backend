package pl.detailing.crm.visit.settlement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod

/**
 * Przypadki brzegowe poprawki rozliczenia w trybie „Faktury wystawia księgowość":
 * kolejne poprawki jedna po drugiej, usługa za darmo, zmiana samego nabywcy, gotówka,
 * wyłączenie trybu przy fakturze księgowości.
 *
 * Każdy test sprawdza, co liczy się do przychodu w Finansach
 * ([SettlementHarness.financeRevenueNet]) i co dzieje się z kasą. Rozjazd to ta sama
 * sprzedaż policzona dwa razy albo wcale.
 */
class SettlementExternalInvoiceEdgeCasesTest {

    private val h = SettlementHarness().apply { invoicesExternal = true }

    private fun active() = h.documents.filter { it.correctsDocumentId == null && it.supersededAt == null }

    @Test
    fun `zmiana ceny przy fakturze ksiegowosci - nowy zapis platnosci i uwaga o korekcie u ksiegowosci`() {
        val placeholder = h.externalInvoice(PaymentMethod.CARD)
        val cmd = h.command(DocumentType.INVOICE, PaymentMethod.CARD, prices = h.price(40_000))

        assertTrue(h.preview(cmd).steps.any { placeholder.documentNumber in it && "CRM nie wystawi korekty" in it })
        h.execute(cmd)

        val fresh = active().single()
        assertTrue(fresh.invoicedExternally)
        assertEquals(49_200, fresh.totalGross)
        assertTrue(h.issuedInvoices.isEmpty() && h.issuedCorrections.isEmpty(), "KSeF z CRM nie rusza")
        assertEquals(0, h.financeRevenueNet)
    }

    @Test
    fun `druga poprawka pod rzad - obowiazuje jeden zapis platnosci z ostatnia kwota`() {
        h.externalInvoice(PaymentMethod.CARD)
        h.execute(h.command(DocumentType.INVOICE, PaymentMethod.CARD, prices = h.price(40_000)))

        h.execute(h.command(DocumentType.INVOICE, PaymentMethod.CARD, prices = h.price(30_000)))

        assertEquals(36_900, active().single().totalGross)
        assertEquals(0, h.financeRevenueNet)
    }

    @Test
    fun `usluga za darmo po fakcie - zapis platnosci zestornowany, nic nowego`() {
        val placeholder = h.externalInvoice(PaymentMethod.CARD)

        h.execute(h.command(DocumentType.INVOICE, PaymentMethod.CARD, prices = h.price(0)))

        assertNotNull(placeholder.supersededAt)
        assertTrue(active().isEmpty(), "bez nowego dokumentu na 0 zł")
        assertEquals(0, h.financeRevenueNet)
    }

    @Test
    fun `zmiana samego nabywcy - nowy zapis platnosci z nowym nabywca`() {
        val placeholder = h.externalInvoice(PaymentMethod.CARD, buyerName = "Jan Kowalski")

        h.execute(h.command(DocumentType.INVOICE, PaymentMethod.CARD, buyer = SettlementBuyer(nip = "5261040828", name = "Auto Serwis")))

        assertNotNull(placeholder.supersededAt)
        val fresh = active().single()
        assertEquals("Auto Serwis", fresh.counterpartyName)
        assertEquals("5261040828", fresh.counterpartyNip)
        assertEquals(61_500, fresh.totalGross)
        assertTrue(fresh.invoicedExternally)
    }

    @Test
    fun `ten sam nabywca i nic poza tym - poprawka jest blokowana, a nie tworzy duplikatu`() {
        h.externalInvoice(PaymentMethod.CARD, buyerName = "Jan Kowalski")

        val preview = h.preview(h.command(DocumentType.INVOICE, PaymentMethod.CARD, buyer = SettlementBuyer(name = "Jan Kowalski")))

        assertNotNull(preview.blockReason)
        assertEquals(1, h.documents.size)
    }

    @Test
    fun `gotowka na karte przy fakturze ksiegowosci - kasa oddaje kwote`() {
        h.externalInvoice(PaymentMethod.CASH)
        val cmd = h.command(DocumentType.INVOICE, PaymentMethod.CARD)

        assertEquals(-61_500, h.preview(cmd).cashDelta)
        h.execute(cmd)

        assertEquals(-61_500, h.cashMovement, "storno gotówki, kopia kartą")
        assertTrue(active().single().invoicedExternally)
        assertEquals(0, h.financeRevenueNet)
    }

    @Test
    fun `faktura ksiegowosci na dokument inny - dokument inny liczy sie do przychodu raz`() {
        h.externalInvoice(PaymentMethod.CARD)

        h.execute(h.command(DocumentType.OTHER, PaymentMethod.CARD))

        val other = h.documents.single { it.documentType == DocumentType.OTHER }
        assertFalse(other.invoicedExternally)
        assertEquals(50_000, h.financeRevenueNet)
    }

    @Test
    fun `tryb wylaczony przy fakturze ksiegowosci - CRM wystawia nowa, podglad kaze przekazac zmiane ksiegowosci`() {
        h.invoicesExternal = false
        val placeholder = h.externalInvoice(PaymentMethod.CARD)
        val cmd = h.command(DocumentType.INVOICE, PaymentMethod.CARD, prices = h.price(40_000))

        assertTrue(h.preview(cmd).steps.any { placeholder.documentNumber in it && "Przekaż jej tę zmianę" in it })
        h.execute(cmd)

        assertEquals(1, h.issuedInvoices.size, "nowa faktura z CRM")
        val newDoc = h.documents.single { it.documentType == DocumentType.INVOICE && it.correctsDocumentId == null && it.supersededAt == null }
        assertFalse(newDoc.invoicedExternally)
        assertNotNull(newDoc.ksefRevenueInvoiceId, "przychód niesie faktura z CRM, nie dokument")
        assertEquals(0, h.financeRevenueNet)
    }

    @Test
    fun `po poprawce na paragon kolejna poprawka na fakture nie jest blokowana`() {
        h.externalInvoice(PaymentMethod.CARD)
        h.execute(h.command(DocumentType.RECEIPT, PaymentMethod.CARD))

        val state = h.service.loadState(h.studioId.value, h.visit.id.value)

        assertEquals(listOf(DocumentType.RECEIPT), state.activeDocuments.map { it.documentType })
        assertNull(state.externalBuyer, "zestornowany zapis płatności nie jest już nabywcą")
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
