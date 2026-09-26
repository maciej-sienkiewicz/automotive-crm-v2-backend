package pl.detailing.crm.visit.settlement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus
import pl.detailing.crm.ksef.revenue.domain.RevenueInvoiceType

/**
 * „Podgląd faktury” w nagłówku wizyty pokazuje wyłącznie fakturę, która obowiązuje.
 *
 * Zgłoszenie z produkcji: wizyta zakończona fakturą, poprawiona na paragon za gotówkę —
 * a nagłówek dalej proponował podgląd starej faktury, wyzerowanej już korektą.
 */
class LiveRevenueInvoicesTest {

    private val h = SettlementHarness()

    /** To samo, co liczy GetVisitDetailHandler: faktura przypięta do obowiązującego dokumentu. */
    private fun preview() = LiveRevenueInvoices.previewInvoiceId(
        h.invoices,
        h.documents
            .filter { it.deletedAt == null && it.supersededAt == null && it.documentType != DocumentType.CORRECTION }
            .firstNotNullOfOrNull { it.ksefRevenueInvoiceId }
    )

    @Test
    fun `zwykla wizyta rozliczona faktura - podglad tej faktury`() {
        val (invoice, _) = h.invoiceWithDocument(KsefRevenueStatus.ACCEPTED)
        assertEquals(invoice.id, preview())
    }

    @Test
    fun `faktura przyjeta poprawiona na paragon za gotowke - podgladu faktury nie ma`() {
        h.invoiceWithDocument(KsefRevenueStatus.ACCEPTED, method = PaymentMethod.CARD)
        h.execute(h.command(type = DocumentType.RECEIPT, method = PaymentMethod.CASH))
        assertNull(preview(), "faktura wyzerowana korektą nie obowiązuje")
    }

    @Test
    fun `faktura przyjeta poprawiona na dokument inny - podgladu faktury nie ma`() {
        h.invoiceWithDocument(KsefRevenueStatus.ACCEPTED)
        h.execute(h.command(type = DocumentType.OTHER, method = PaymentMethod.CASH))
        assertNull(preview())
    }

    @Test
    fun `faktura niewyslana poprawiona na paragon - anulowana faktura nie wraca do podgladu`() {
        h.invoiceWithDocument(KsefRevenueStatus.NOT_SENT)
        h.execute(h.command(type = DocumentType.RECEIPT, method = PaymentMethod.CASH))
        assertNull(preview())
    }

    @Test
    fun `korekta nie powstala - stara faktura wciaz obowiazuje i da sie ja podejrzec`() {
        val (invoice, _) = h.invoiceWithDocument(KsefRevenueStatus.ACCEPTED)
        h.failCorrection = IllegalStateException("KSeF niedostępny")
        h.execute(h.command(type = DocumentType.RECEIPT, method = PaymentMethod.CASH))
        assertEquals(invoice.id, preview())
    }

    @Test
    fun `korekta odrzucona przez KSeF nie zeruje faktury`() {
        val (invoice, _) = h.invoiceWithDocument(KsefRevenueStatus.ACCEPTED)
        h.invoice(KsefRevenueStatus.REJECTED, gross = -invoice.totalGross, number = "FK/2026/0001",
            type = RevenueInvoiceType.KOR, originalId = invoice.id)
        assertEquals(invoice.id, preview())
    }

    @Test
    fun `po korekcie i nowej fakturze - podglad nowej faktury`() {
        val (old, _) = h.invoiceWithDocument(KsefRevenueStatus.ACCEPTED)
        h.invoice(KsefRevenueStatus.ACCEPTED, gross = -old.totalGross, number = "FK/2026/0001",
            type = RevenueInvoiceType.KOR, originalId = old.id)
        val reissued = h.invoice(KsefRevenueStatus.PENDING, gross = 49_200, number = "FV/2026/0004")
        assertEquals(reissued.id, preview())
    }

    @Test
    fun `paragon z faktura do paragonu - podglad faktury do paragonu`() {
        h.cashReceipt()
        val toReceipt = h.invoice(KsefRevenueStatus.ACCEPTED, toReceipt = true)
        assertEquals(toReceipt.id, preview())
    }

    @Test
    fun `faktura odrzucona przypieta do obowiazujacego dokumentu - podglad zostaje, zeby widac bylo powod`() {
        val rejected = h.invoice(KsefRevenueStatus.REJECTED)
        h.document(DocumentType.INVOICE, PaymentMethod.CARD, ksefId = rejected.id)
        assertEquals(rejected.id, preview())
    }

    @Test
    fun `sam paragon, anulowana faktura bez dokumentu - podgladu nie ma`() {
        h.cashReceipt()
        h.invoice(KsefRevenueStatus.CANCELLED)
        assertNull(preview())
    }
}
