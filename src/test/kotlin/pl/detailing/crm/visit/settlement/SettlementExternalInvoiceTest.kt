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
import pl.detailing.crm.shared.ValidationException
import java.time.LocalDate

/**
 * Poprawka rozliczenia w trybie „Faktury wystawia księgowość".
 *
 * Niezmiennik, którego pilnuje każdy test: przychód w Finansach z dokumentów CRM
 * ([SettlementHarness.financeRevenueNet]) nie liczy sprzedaży, której fakturę wystawia
 * księgowość — ta wejdzie do sum fakturą pobraną z KSeF. CRM nie prowadzi dla księgowości
 * żadnej listy: fakturę księgowości koryguje księgowość, a podgląd mówi, że trzeba jej
 * przekazać zmianę.
 */
class SettlementExternalInvoiceTest {

    private val h = SettlementHarness().apply { invoicesExternal = true }

    @Test
    fun `paragon na fakture ze zmiana ceny - zapis platnosci zamiast faktury z CRM`() {
        h.cashReceipt()
        val cmd = h.command(DocumentType.INVOICE, PaymentMethod.CARD, prices = h.price(40_000))

        val preview = h.preview(cmd)
        assertNull(preview.blockReason)
        assertTrue(preview.steps.any { "Fakturę wystawia księgowość" in it })
        assertTrue(preview.steps.none { "zafakturowania" in it })

        h.execute(cmd)

        assertTrue(h.issuedInvoices.isEmpty(), "CRM nie wystawia faktury")
        val placeholder = h.documents.single { it.documentType == DocumentType.INVOICE }
        assertTrue(placeholder.invoicedExternally)
        assertEquals(49_200, placeholder.totalGross, "kwota wizyty po poprawce")
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
    fun `faktura ksiegowosci na paragon - paragon wraca do przychodu, podglad kaze przekazac zmiane ksiegowosci`() {
        val placeholder = h.externalInvoice(PaymentMethod.CARD)
        val cmd = h.command(DocumentType.RECEIPT, PaymentMethod.CARD)

        assertTrue(h.preview(cmd).steps.any { placeholder.documentNumber in it && "Przekaż jej tę zmianę" in it })
        h.execute(cmd)

        assertNotNull(placeholder.supersededAt)
        val storno = h.documents.single { it.correctsDocumentId == placeholder.id }
        assertTrue(storno.invoicedExternally, "storno dokumentu spoza przychodu też jest poza przychodem")
        assertTrue(h.issuedCorrections.isEmpty(), "korektę faktury księgowości wystawia księgowość, nie CRM")
        assertEquals(50_000, h.financeRevenueNet, "nowy paragon liczy się raz")
    }

    @Test
    fun `sama forma platnosci - kopia zostaje poza przychodem, bez uwagi o korekcie`() {
        val placeholder = h.externalInvoice(PaymentMethod.CARD, buyerName = "Auto Serwis", buyerNip = "5261040828")
        val cmd = h.command(
            DocumentType.INVOICE, PaymentMethod.TRANSFER, dueDate = LocalDate.now().plusDays(7),
            buyer = SettlementBuyer(nip = "5261040828", name = "Auto Serwis")
        )

        assertTrue(h.preview(cmd).steps.none { "Przekaż jej" in it }, "forma płatności faktury nie dotyczy")
        h.execute(cmd)

        val clone = h.documents.single { it.id != placeholder.id && it.correctsDocumentId == null }
        assertTrue(clone.invoicedExternally)
        assertEquals(PaymentMethod.TRANSFER, clone.paymentMethod)
        assertEquals("Auto Serwis", clone.counterpartyName)
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
        assertEquals("5261040828", clone.counterpartyNip, "nabywca faktury księgowości na zapisie płatności")
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
        val receipt = h.externalInvoice(PaymentMethod.CARD, type = DocumentType.RECEIPT, buyerName = "Jan Kowalski")

        h.execute(h.command(DocumentType.INVOICE, PaymentMethod.CARD, buyer = SettlementBuyer(name = "Anna Nowak")))

        assertEquals(1, h.issuedInvoices.size)
        assertTrue(h.issuedInvoices.single().invoiceToReceipt)
        assertNotNull(receipt.supersededAt)
        assertEquals(50_000, h.financeRevenueNet, "paragon liczy się raz, faktura do paragonu nie jest drugą sprzedażą")
    }

    @Test
    fun `faktura z CRM sprzed wlaczenia trybu poprawia sie po staremu`() {
        val (invoice, _) = h.invoiceWithDocument(pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus.ACCEPTED)

        h.execute(h.command(DocumentType.INVOICE, PaymentMethod.CARD, prices = h.price(40_000)))

        assertEquals(invoice.id, h.issuedCorrections.single().originalInvoiceId, "korekta do zera w KSeF jak dotąd")
        assertTrue(h.issuedInvoices.isEmpty(), "nową fakturę wystawia już księgowość")
        assertTrue(h.documents.single { it.correctsDocumentId == null && it.supersededAt == null }.invoicedExternally)
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
        val before = h.documents.size

        assertThrows<ValidationException> {
            h.execute(h.command(DocumentType.INVOICE, PaymentMethod.CASH, buyer = SettlementBuyer(name = "Jan Kowalski")))
        }
        assertEquals(before, h.documents.size)
    }
}
