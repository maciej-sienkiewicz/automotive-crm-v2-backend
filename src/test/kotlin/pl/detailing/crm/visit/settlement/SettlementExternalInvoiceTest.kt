package pl.detailing.crm.visit.settlement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.external.ExternalInvoiceKind
import pl.detailing.crm.finance.external.ExternalInvoiceStatus
import pl.detailing.crm.shared.ValidationException
import java.time.LocalDate

/**
 * Poprawka rozliczenia w trybie „Faktury wystawia księgowość".
 *
 * Niezmiennik, którego pilnuje każdy test: przychód w Finansach z dokumentów CRM
 * ([SettlementHarness.financeRevenueNet]) nie liczy sprzedaży, której fakturę wystawia
 * księgowość — ta wejdzie do sum fakturą pobraną z KSeF. Nic nie łączy się samo:
 * zgłoszenia są wycofywane albo rodzą zgłoszenie korekty, a odhacza je człowiek.
 */
class SettlementExternalInvoiceTest {

    private val h = SettlementHarness().apply { invoicesExternal = true }

    @Test
    fun `paragon na fakture ze zmiana ceny - zapis dla ksiegowosci zamiast faktury z CRM`() {
        h.cashReceipt()
        val cmd = h.command(DocumentType.INVOICE, PaymentMethod.CARD, prices = h.price(40_000))

        val preview = h.preview(cmd)
        assertNull(preview.blockReason)
        assertTrue(preview.steps.any { "Do zafakturowania" in it && "księgowość" in it })

        h.execute(cmd)

        assertTrue(h.issuedInvoices.isEmpty(), "CRM nie wystawia faktury")
        val placeholder = h.documents.single { it.documentType == DocumentType.INVOICE }
        assertTrue(placeholder.invoicedExternally)
        assertEquals(49_200, placeholder.totalGross, "kwota wizyty po poprawce")
        val request = h.requests.single()
        assertEquals(ExternalInvoiceKind.INVOICE, request.kind)
        assertEquals(ExternalInvoiceStatus.PENDING, request.status)
        assertEquals(placeholder.id, request.financialDocumentId)
        assertEquals(49_200, request.totalGross)
        assertEquals(SettlementKsefAction.EXTERNAL_INVOICE, h.savedCorrections.single().ksefAction)
        assertEquals(0, h.financeRevenueNet, "paragon zestornowany, faktura księgowości jeszcze nie przyszła")
    }

    @Test
    fun `bez danych firmy studia - faktura od ksiegowosci nie jest blokowana`() {
        h.companyComplete = false
        h.cashReceipt()

        val preview = h.preview(h.command(DocumentType.INVOICE, PaymentMethod.CARD, prices = h.price(40_000)))

        assertNull(preview.blockReason)
    }

    @Test
    fun `faktura czekajaca na ksiegowosc na paragon - zgloszenie wycofane, paragon wraca do przychodu`() {
        val (placeholder, request) = h.externalInvoice(PaymentMethod.CARD)

        h.execute(h.command(DocumentType.RECEIPT, PaymentMethod.CARD))

        assertEquals(ExternalInvoiceStatus.WITHDRAWN, request.status)
        assertNotNull(placeholder.supersededAt)
        val storno = h.documents.single { it.correctsDocumentId == placeholder.id }
        assertTrue(storno.invoicedExternally, "storno dokumentu spoza przychodu też jest poza przychodem")
        assertEquals(1, h.requests.size, "bez korekty - faktury jeszcze nie było")
        assertEquals(50_000, h.financeRevenueNet, "nowy paragon liczy się raz")
    }

    @Test
    fun `faktura juz wystawiona przez ksiegowosc na paragon - zgloszenie korekty dla ksiegowosci`() {
        val (placeholder, request) = h.externalInvoice(PaymentMethod.CARD, issuedNumber = "FV 12/09/2026")
        val cmd = h.command(DocumentType.RECEIPT, PaymentMethod.CARD)

        assertTrue(h.preview(cmd).steps.any { "FV 12/09/2026" in it && "korekty" in it })
        h.execute(cmd)

        assertEquals(ExternalInvoiceStatus.ISSUED, request.status, "wystawiona faktura zostaje wystawiona")
        val correction = h.requests.single { it.kind == ExternalInvoiceKind.CORRECTION }
        assertEquals(request.id, correction.correctsRequestId)
        assertEquals(ExternalInvoiceStatus.PENDING, correction.status)
        assertEquals(-61_500, correction.totalGross)
        assertEquals(-50_000, correction.totalNet)
        val storno = h.documents.single { it.correctsDocumentId == placeholder.id }
        assertEquals(storno.id, correction.financialDocumentId)
        assertTrue(h.issuedCorrections.isEmpty(), "korektę wystawia księgowość, nie CRM")
    }

    @Test
    fun `sama forma platnosci - zgloszenie przechodzi na nowy dokument bez zmian`() {
        val (placeholder, request) = h.externalInvoice(PaymentMethod.CARD, issuedNumber = "FV 12/09/2026")

        h.execute(h.command(DocumentType.INVOICE, PaymentMethod.TRANSFER, dueDate = LocalDate.now().plusDays(7)))

        val clone = h.documents.single { it.id != placeholder.id && it.correctsDocumentId == null }
        assertTrue(clone.invoicedExternally)
        assertEquals(PaymentMethod.TRANSFER, clone.paymentMethod)
        assertEquals(clone.id, request.financialDocumentId)
        assertEquals(ExternalInvoiceStatus.ISSUED, request.status)
        assertEquals(1, h.requests.size, "bez korekty i bez nowego zgłoszenia")
        assertEquals(0, h.financeRevenueNet)
    }

    @Test
    fun `paragon na fakture bez zmiany kwot - fakture do paragonu wystawia ksiegowosc`() {
        val receipt = h.cashReceipt()
        val cmd = h.command(DocumentType.INVOICE, PaymentMethod.CASH, buyer = SettlementBuyer(nip = "5261040828", name = "Auto Serwis"))

        val preview = h.preview(cmd)
        assertNull(preview.blockReason)
        assertEquals(0, preview.cashDelta, "gotówka zostaje w kasie")
        h.execute(cmd)

        assertTrue(h.issuedInvoices.isEmpty())
        assertNotNull(receipt.supersededAt)
        val clone = h.documents.single { it.documentType == DocumentType.RECEIPT && it.id != receipt.id && it.correctsDocumentId == null }
        assertTrue(clone.invoicedExternally)
        val request = h.requests.single()
        assertEquals(ExternalInvoiceKind.INVOICE_TO_RECEIPT, request.kind)
        assertEquals("5261040828", request.buyerNip)
        assertEquals(61_500, request.totalGross)
        assertEquals(SettlementKsefAction.EXTERNAL_INVOICE_TO_RECEIPT, h.savedCorrections.single().ksefAction)
        assertEquals(0, h.financeRevenueNet, "przychód niesie teraz faktura do paragonu od księgowości")
        assertEquals(0, h.cashMovement, "storno i kopia gotówkowa znoszą się w kasie")
    }

    @Test
    fun `druga faktura do tego samego paragonu z tymi samymi danymi jest blokowana`() {
        h.externalInvoice(PaymentMethod.CASH, type = DocumentType.RECEIPT, buyerName = "Jan Kowalski")

        val preview = h.preview(h.command(DocumentType.INVOICE, PaymentMethod.CASH, buyer = SettlementBuyer(name = "Jan Kowalski")))

        assertNotNull(preview.blockReason)
        assertTrue(preview.blockReason!!.contains("księgowość"))
    }

    @Test
    fun `tryb wylaczony - paragon czekajacy na ksiegowosc dostaje fakture z CRM i wraca do przychodu`() {
        h.invoicesExternal = false
        val (receipt, request) = h.externalInvoice(PaymentMethod.CARD, type = DocumentType.RECEIPT, buyerName = "Jan Kowalski")

        h.execute(h.command(DocumentType.INVOICE, PaymentMethod.CARD, buyer = SettlementBuyer(name = "Anna Nowak")))

        assertEquals(1, h.issuedInvoices.size)
        assertTrue(h.issuedInvoices.single().invoiceToReceipt)
        assertEquals(ExternalInvoiceStatus.WITHDRAWN, request.status)
        assertNotNull(receipt.supersededAt)
        assertEquals(50_000, h.financeRevenueNet, "paragon liczy się raz, faktura do paragonu nie jest drugą sprzedażą")
    }

    @Test
    fun `faktura z CRM sprzed wlaczenia trybu poprawia sie po staremu`() {
        val (invoice, _) = h.invoiceWithDocument(pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus.ACCEPTED)

        h.execute(h.command(DocumentType.INVOICE, PaymentMethod.CARD, prices = h.price(40_000)))

        assertEquals(invoice.id, h.issuedCorrections.single().originalInvoiceId, "korekta do zera w KSeF jak dotąd")
        assertTrue(h.issuedInvoices.isEmpty(), "nową fakturę wystawia już księgowość")
        assertEquals(ExternalInvoiceKind.INVOICE, h.requests.single().kind)
    }

    @Test
    fun `nabywca faktury ksiegowosci wymagany`() {
        h.cashReceipt()

        val preview = h.preview(
            h.command(DocumentType.INVOICE, PaymentMethod.CARD, prices = h.price(40_000), buyer = SettlementBuyer())
        )

        assertNotNull(preview.blockReason)
        assertFalse(preview.blockReason!!.isBlank())
    }

    @Test
    fun `wykonanie zablokowanego planu rzuca wyjatek i nic nie zapisuje`() {
        h.externalInvoice(PaymentMethod.CASH, type = DocumentType.RECEIPT)

        assertThrows<ValidationException> {
            h.execute(h.command(DocumentType.INVOICE, PaymentMethod.CASH, buyer = SettlementBuyer(name = "Jan Kowalski")))
        }
        assertEquals(1, h.requests.size)
    }
}
