package pl.detailing.crm.visit.settlement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VatRate
import java.time.LocalDate

/**
 * Wykonanie poprawki: co powstaje, co zostaje, ile trafia do kasy, co idzie do KSeF.
 * Zasada we wszystkich przypadkach: nic nie jest usuwane, suma dokumentów wizyty
 * (stare + storna + nowe) równa się nowej kwocie wizyty.
 */
class SettlementExecutionTest {

    private val h = SettlementHarness()

    /** Suma brutto wszystkich dokumentów przychodowych wizyty po poprawce — musi dać nową kwotę. */
    private fun documentsTotal() = h.documents.filter { it.deletedAt == null }.sumOf { it.totalGross }

    // ── Paragon ────────────────────────────────────────────────────────────────

    @Test
    fun `rabat po fakcie na paragonie gotowkowym - zwrot z kasy zgadza sie z podgladem`() {
        val receipt = h.cashReceipt()
        val cmd = h.command(prices = h.price(40_650, 50_000))
        val preview = h.preview(cmd)

        h.execute(cmd)

        assertNotNull(receipt.supersededAt)
        assertEquals(50_000, documentsTotal())
        // Kasa: storno −615,00 i nowy paragon +500,00 = −115,00, dokładnie jak w podglądzie.
        assertEquals(preview.cashDelta, h.cashMovement)
        assertEquals(50_000, h.visit.calculateTotalGross().amountInCents)
    }

    @Test
    fun `doplata - klient placi wiecej, gotowka w kasie rosnie o roznice`() {
        h.cashReceipt()
        h.execute(h.command(prices = h.price(60_000)))
        assertEquals(73_800, documentsTotal())
        assertEquals(73_800 - 61_500, h.cashMovement)
    }

    @Test
    fun `gotowka na karte bez zmiany kwot - storno i kopia z karta, kasa oddaje cala kwote`() {
        val receipt = h.cashReceipt()
        h.execute(h.command(method = PaymentMethod.CARD))

        assertEquals(listOf(DocumentType.CORRECTION, DocumentType.RECEIPT), h.created.map { it.documentType })
        assertEquals(PaymentMethod.CARD, h.created[1].paymentMethod)
        assertEquals(receipt.totalGross, h.created[1].totalGross)
        assertEquals(-61_500, h.cashMovement)
        assertTrue(h.savedVisits.isEmpty(), "ceny się nie zmieniają")
    }

    @Test
    fun `karta na przelew - nowy dokument czeka na zaplate, kasa bez ruchu`() {
        h.document(DocumentType.RECEIPT, PaymentMethod.CARD)
        h.execute(h.command(method = PaymentMethod.TRANSFER, dueDate = LocalDate.now().plusDays(14)))

        val replacement = h.documents.last()
        assertEquals(DocumentStatus.PENDING, replacement.status)
        assertEquals(LocalDate.now().plusDays(14), replacement.dueDate)
        assertEquals(0, h.cashMovement)
    }

    @Test
    fun `storno przelewu nieoplaconego zostaje nieoplacone i nie rusza kasy`() {
        h.document(DocumentType.RECEIPT, PaymentMethod.TRANSFER, status = DocumentStatus.PENDING)
        h.execute(h.command(method = PaymentMethod.CASH))

        val storno = h.created.first { it.documentType == DocumentType.CORRECTION }
        assertEquals(DocumentStatus.PENDING, storno.statusOverride)
        assertEquals(61_500, h.cashMovement, "wchodzi tylko nowa gotówka")
    }

    @Test
    fun `paragon na dokument inny - storno i nowy dokument inny`() {
        h.cashReceipt()
        h.execute(h.command(type = DocumentType.OTHER))
        assertEquals(listOf(DocumentType.CORRECTION, DocumentType.OTHER), h.created.map { it.documentType })
        assertEquals(0, h.cashMovement, "gotówka wychodzi stornem i wraca nowym dokumentem")
    }

    @Test
    fun `usluga za darmo po fakcie - samo storno, bez nowego dokumentu`() {
        h.cashReceipt()
        h.execute(h.command(prices = h.price(0)))
        assertEquals(listOf(DocumentType.CORRECTION), h.created.map { it.documentType })
        assertEquals(0, documentsTotal())
        assertEquals(-61_500, h.cashMovement)
    }

    @Test
    fun `wizyta wydana bez dokumentu - poprawka ceny wystawia brakujacy paragon`() {
        h.execute(h.command(prices = h.price(40_000)))
        assertEquals(listOf(DocumentType.RECEIPT), h.created.map { it.documentType })
        assertEquals(49_200, h.created.single().totalGross)
    }

    @Test
    fun `wizyta wydana bez dokumentu - sam wybor paragonu dopisuje dokument`() {
        h.execute(h.command(type = DocumentType.RECEIPT))
        assertEquals(listOf(DocumentType.RECEIPT), h.created.map { it.documentType })
        assertEquals(61_500, h.created.single().totalGross)
    }

    @Test
    fun `bez modulu Finanse - zmieniaja sie tylko ceny wizyty, dokumentow nie ma`() {
        h.hasFinance = false
        h.execute(h.command(prices = h.price(40_000)))
        assertTrue(h.created.isEmpty())
        assertEquals(49_200, h.visit.calculateTotalGross().amountInCents)
    }

    @Test
    fun `pozycja z rabatem nieruszana zachowuje rabat, zmieniona traci go`() {
        val discounted = h.serviceItem(90_000, 110_700).copy(
            basePriceNet = pl.detailing.crm.shared.Money(100_000),
            adjustmentType = pl.detailing.crm.appointment.domain.AdjustmentType.PERCENT, adjustmentValue = -1_000
        )
        val plain = h.serviceItem(50_000, 61_500)
        h.withItems(discounted, plain)
        h.document(DocumentType.RECEIPT, PaymentMethod.CARD, gross = 172_200, net = 140_000)

        h.execute(h.command(method = PaymentMethod.CARD, prices = h.price(40_000, id = plain.id)))

        val after = h.visit.serviceItems
        assertEquals(pl.detailing.crm.appointment.domain.AdjustmentType.PERCENT, after.first { it.id == discounted.id }.adjustmentType)
        assertEquals(110_700 + 49_200, h.created.last().totalGross)
    }

    // ── Faktura KSeF ───────────────────────────────────────────────────────────

    @Test
    fun `faktura niewyslana, w kolejce albo odrzucona - anulowana i nowa faktura`() {
        listOf(KsefRevenueStatus.NOT_SENT, KsefRevenueStatus.PENDING, KsefRevenueStatus.QUEUED_RETRY).forEach { status ->
            val h = SettlementHarness()
            val (inv, doc) = h.invoiceWithDocument(status)
            h.execute(h.command(type = DocumentType.INVOICE, method = PaymentMethod.CARD, prices = h.price(40_650, 50_000)))

            assertEquals(listOf(inv.id), h.cancelled, "status $status")
            assertTrue(h.issuedCorrections.isEmpty())
            assertEquals(1, h.issuedInvoices.size)
            assertNotNull(doc.supersededAt)
            val storno = h.documents.first { it.correctsDocumentId == doc.id }
            assertEquals(inv.id, storno.ksefRevenueInvoiceId, "storno w łańcuchu anulowanej faktury — kafle nie liczą go drugi raz")
        }
    }

    @Test
    fun `wysylka zajela fakture w trakcie poprawki - konflikt i wycofanie calosci`() {
        h.invoiceWithDocument(KsefRevenueStatus.QUEUED_RETRY)
        h.cancelResult = 0

        assertThrows<ConflictException> {
            h.execute(h.command(type = DocumentType.INVOICE, method = PaymentMethod.CARD, prices = h.price(40_000)))
        }
        assertEquals(1, h.transactions.rollbacks)
        assertEquals(0, h.transactions.commits)
        assertTrue(h.issuedInvoices.isEmpty())
        assertTrue(h.audits.isEmpty())
    }

    @Test
    fun `faktura przyjeta - korekta do zera przed nowa faktura, z powodem uzytkownika`() {
        val (inv, _) = h.invoiceWithDocument(KsefRevenueStatus.ACCEPTED)
        val result = h.execute(h.command(type = DocumentType.INVOICE, method = PaymentMethod.CARD, prices = h.price(40_000)))

        assertEquals(inv.id, h.issuedCorrections.single().originalInvoiceId)
        assertEquals("Rabat po fakcie", h.issuedCorrections.single().reason)
        assertNull(h.issuedCorrections.single().items)
        assertEquals(1, h.issuedInvoices.size)
        assertNull(result.ksefError)
        val correction = h.savedCorrections.single()
        assertEquals(SettlementKsefAction.CORRECT_AND_REISSUE, correction.ksefAction)
        assertNotNull(correction.ksefCorrectionInvoiceId)
        assertNotNull(correction.newKsefInvoiceId)
    }

    @Test
    fun `korekta KSeF bez powodu dostaje powod domyslny - KSeF go wymaga`() {
        h.invoiceWithDocument(KsefRevenueStatus.ACCEPTED)
        h.execute(h.command(type = DocumentType.INVOICE, method = PaymentMethod.CARD, prices = h.price(40_000), reason = "  "))
        assertEquals("Poprawka rozliczenia wizyty", h.issuedCorrections.single().reason)
        assertNull(h.savedCorrections.single().reason)
    }

    @Test
    fun `zmiana samego nabywcy na fakturze przyjetej - korekta i nowa faktura z nowym NIP`() {
        h.invoiceWithDocument(KsefRevenueStatus.ACCEPTED)
        h.execute(h.command(
            type = DocumentType.INVOICE, method = PaymentMethod.CARD,
            buyer = SettlementBuyer(nip = "526-104-08-28", name = "Auto-Komis sp. z o.o.")
        ))
        assertEquals(1, h.issuedCorrections.size)
        assertEquals("5261040828", h.issuedInvoices.single().buyer.nip)
    }

    @Test
    fun `sama forma platnosci na fakturze - faktura bez zmian, status zaplaty idzie za forma`() {
        val (inv, _) = h.invoiceWithDocument(KsefRevenueStatus.ACCEPTED, method = PaymentMethod.TRANSFER)
        inv.paymentStatus = "PENDING"
        h.execute(h.command(type = DocumentType.INVOICE, method = PaymentMethod.CASH))

        assertTrue(h.issuedCorrections.isEmpty())
        assertTrue(h.issuedInvoices.isEmpty())
        assertTrue(h.cancelled.isEmpty())
        assertEquals("PAID", inv.paymentStatus)
        assertNotNull(inv.paidAt)
        assertEquals(inv.id, h.created.last().ksefRevenueInvoiceId)
        assertEquals(SettlementKsefAction.KEEP, h.savedCorrections.single().ksefAction)
    }

    @Test
    fun `faktura z paragonem reszty, sama forma platnosci - oba dokumenty wracaja z nowa forma i te same kwoty`() {
        val (inv, _) = h.invoiceWithDocument(KsefRevenueStatus.ACCEPTED)
        h.document(DocumentType.RECEIPT, PaymentMethod.CARD, gross = 10_000, net = 8_130)

        h.execute(h.command(type = DocumentType.INVOICE, method = PaymentMethod.CASH))

        val copies = h.created.filter { it.documentType != DocumentType.CORRECTION }
        assertEquals(listOf(61_500L, 10_000L), copies.map { it.totalGross })
        assertEquals(inv.id, copies[0].ksefRevenueInvoiceId)
        assertNull(copies[1].ksefRevenueInvoiceId)
        assertTrue(copies.all { it.paymentMethod == PaymentMethod.CASH })
    }

    @Test
    fun `faktura przyjeta na paragon - korekta do zera i paragon, bez nowej faktury`() {
        h.invoiceWithDocument(KsefRevenueStatus.ACCEPTED, method = PaymentMethod.CASH)
        h.execute(h.command(type = DocumentType.RECEIPT, method = PaymentMethod.CASH))

        assertEquals(1, h.issuedCorrections.size)
        assertTrue(h.issuedInvoices.isEmpty())
        assertEquals(listOf(DocumentType.CORRECTION, DocumentType.RECEIPT), h.created.map { it.documentType })
        assertEquals(0, h.cashMovement, "gotówka ta sama — zmienia się dokument, nie pieniądze")
        assertEquals(SettlementKsefAction.CORRECT, h.savedCorrections.single().ksefAction)
    }

    @Test
    fun `faktura niewyslana na paragon - anulowanie bez nowej faktury`() {
        h.invoiceWithDocument(KsefRevenueStatus.NOT_SENT)
        h.execute(h.command(type = DocumentType.RECEIPT, method = PaymentMethod.CARD))
        assertEquals(1, h.cancelled.size)
        assertTrue(h.issuedInvoices.isEmpty())
        assertEquals(SettlementKsefAction.CANCEL, h.savedCorrections.single().ksefAction)
    }

    @Test
    fun `faktura odrzucona przez KSeF - traktowana jak brak faktury, nie anulujemy jej ani nie korygujemy`() {
        val rejected = h.invoice(KsefRevenueStatus.REJECTED)
        h.document(DocumentType.INVOICE, PaymentMethod.CARD, ksefId = rejected.id)

        h.execute(h.command(type = DocumentType.INVOICE, method = PaymentMethod.CARD, prices = h.price(40_000)))

        assertTrue(h.cancelled.isEmpty())
        assertTrue(h.issuedCorrections.isEmpty())
        assertEquals(1, h.issuedInvoices.size)
    }

    // ── Paragon ↔ faktura ──────────────────────────────────────────────────────

    @Test
    fun `paragon na fakture bez zmiany kwot - faktura do paragonu, paragon zostaje`() {
        val receipt = h.cashReceipt()
        h.execute(h.command(type = DocumentType.INVOICE, method = PaymentMethod.CASH))

        assertTrue(h.issuedInvoices.single().invoiceToReceipt)
        assertNull(receipt.supersededAt)
        assertTrue(h.created.isEmpty())
        assertEquals(SettlementKsefAction.INVOICE_TO_RECEIPT, h.savedCorrections.single().ksefAction)
    }

    @Test
    fun `paragon na fakture i zmiana formy platnosci - faktura do paragonu plus kopia paragonu z nowa forma`() {
        h.cashReceipt()
        h.execute(h.command(type = DocumentType.INVOICE, method = PaymentMethod.CARD))

        assertTrue(h.issuedInvoices.single().invoiceToReceipt)
        assertEquals(listOf(DocumentType.CORRECTION, DocumentType.RECEIPT), h.created.map { it.documentType })
        assertEquals(PaymentMethod.CARD, h.created[1].paymentMethod)
    }

    @Test
    fun `paragon na fakture ze zmiana kwoty - storno paragonu i zwykla faktura na nowa kwote`() {
        h.cashReceipt()
        h.execute(h.command(type = DocumentType.INVOICE, method = PaymentMethod.CASH, prices = h.price(40_650, 50_000)))

        val issued = h.issuedInvoices.single()
        assertFalse(issued.invoiceToReceipt)
        assertEquals(50_000, issued.items.single().unitPriceGross)
        assertEquals(listOf(DocumentType.CORRECTION, DocumentType.INVOICE), h.created.map { it.documentType })
        assertEquals(50_000, h.created[1].totalGross)
    }

    @Test
    fun `paragon z faktura do paragonu, zmiana kwoty - faktura do paragonu dostaje korekte, nowy paragon`() {
        h.cashReceipt()
        val fp = h.invoice(KsefRevenueStatus.ACCEPTED, toReceipt = true)
        h.execute(h.command(type = DocumentType.RECEIPT, method = PaymentMethod.CASH, prices = h.price(40_000)))

        assertEquals(fp.id, h.issuedCorrections.single().originalInvoiceId)
        assertTrue(h.issuedInvoices.isEmpty())
    }

    @Test
    fun `paragon z faktura do paragonu, tylko forma platnosci - faktura zostaje`() {
        h.cashReceipt()
        h.invoice(KsefRevenueStatus.ACCEPTED, toReceipt = true)
        h.execute(h.command(type = DocumentType.RECEIPT, method = PaymentMethod.CARD))

        assertTrue(h.issuedCorrections.isEmpty())
        assertTrue(h.cancelled.isEmpty())
    }

    @Test
    fun `faktura do paragonu dla innego nabywcy - stara korygowana, nowa wystawiona`() {
        h.cashReceipt()
        h.invoice(KsefRevenueStatus.ACCEPTED, toReceipt = true, buyerName = "Jan Kowalski")
        h.execute(h.command(type = DocumentType.INVOICE, method = PaymentMethod.CASH, buyer = SettlementBuyer(name = "Anna Nowak")))

        assertEquals(1, h.issuedCorrections.size)
        assertTrue(h.issuedInvoices.single().invoiceToReceipt)
    }

    // ── Pozycje na fakturze ────────────────────────────────────────────────────

    @Test
    fun `pozycje faktury - brutto wpisane idzie w brutto, netto w netto, zw jako zw`() {
        val grossTyped = h.serviceItem(154_472, 190_000, grossTyped = true)
        val netTyped = h.serviceItem(50_000, 61_500)
        val exempt = h.serviceItem(20_000, 20_000, rate = VatRate.VAT_ZW)
        h.withItems(grossTyped, netTyped, exempt)
        h.document(DocumentType.RECEIPT, PaymentMethod.CARD, gross = 271_500, net = 224_472)

        h.execute(h.command(type = DocumentType.INVOICE, method = PaymentMethod.CARD, prices = h.price(40_000, id = netTyped.id), exemption = "art. 113"))

        val lines = h.issuedInvoices.single().items
        assertEquals(190_000, lines[0].unitPriceGross)
        assertNull(lines[0].unitPriceNet)
        assertEquals(40_000, lines[1].unitPriceNet)
        assertEquals("zw", lines[2].vatRate)
        assertEquals("art. 113", h.issuedInvoices.single().exemptionLegalBasis)
        assertEquals(190_000 + 49_200 + 20_000, h.created.last().totalGross, "dokument faktury = suma faktury")
    }

    @Test
    fun `studio wystawia bez wysylki do KSeF - nowa faktura tez bez wysylki`() {
        h.cashReceipt()
        h.ksefAutoSend = false
        h.execute(h.command(type = DocumentType.INVOICE, method = PaymentMethod.CASH, prices = h.price(40_000)))
        assertFalse(h.issuedInvoices.single().sendToKsef)
    }

    @Test
    fun `platnosc przelewem na fakturze - faktura nieoplacona z terminem`() {
        h.cashReceipt()
        val due = LocalDate.now().plusDays(7)
        h.execute(h.command(type = DocumentType.INVOICE, method = PaymentMethod.TRANSFER, dueDate = due, prices = h.price(40_000)))
        val issued = h.issuedInvoices.single()
        assertFalse(issued.isPaid)
        assertEquals(due, issued.paymentDueDate)
        assertEquals("PRZELEW", issued.paymentForm)
    }

    // ── Błędy KSeF po zapisie ──────────────────────────────────────────────────

    @Test
    fun `korekta nie powstala - nowej faktury nie ma, blad w historii, pieniadze zapisane`() {
        h.invoiceWithDocument(KsefRevenueStatus.ACCEPTED)
        h.failCorrection = IllegalStateException("KSeF niedostępny")

        val result = h.execute(h.command(type = DocumentType.INVOICE, method = PaymentMethod.CARD, prices = h.price(40_000)))

        assertTrue(h.issuedInvoices.isEmpty())
        assertTrue(result.ksefError!!.contains("Nowa faktura nie została wystawiona"))
        assertEquals(1, h.transactions.commits, "wizyta i dokumenty zostają poprawione")
        assertEquals(result.ksefError, h.savedCorrections.single().ksefError)
    }

    @Test
    fun `nowa faktura nie powstala - blad w historii, dokument faktury bez powiazania`() {
        h.cashReceipt()
        h.failInvoice = ValidationException("Kolizja numeru")

        val result = h.execute(h.command(type = DocumentType.INVOICE, method = PaymentMethod.CASH, prices = h.price(40_000)))

        assertTrue(result.ksefError!!.contains("Nowa faktura nie powstała"))
        assertNull(h.documents.last { it.documentType == DocumentType.INVOICE }.ksefRevenueInvoiceId)
    }

    // ── Zapis i historia ───────────────────────────────────────────────────────

    @Test
    fun `wpis historii - przed i po, kto, powod, pozycje w JSON, wspolny identyfikator dokumentow`() {
        h.cashReceipt()
        h.execute(h.command(method = PaymentMethod.CARD, prices = h.price(40_650, 50_000)))

        val entry = h.savedCorrections.single()
        assertEquals(61_500, entry.totalGrossBefore)
        assertEquals(50_000, entry.totalGrossAfter)
        assertEquals(PaymentMethod.CASH, entry.paymentMethodBefore)
        assertEquals(PaymentMethod.CARD, entry.paymentMethodAfter)
        assertEquals("Anna Kowalska", entry.createdByName)
        assertEquals("Rabat po fakcie", entry.reason)
        assertTrue(entry.servicesBefore.contains("61500"))
        assertTrue(entry.servicesAfter.contains("50000"))
        assertTrue(h.created.all { it.settlementCorrectionId == entry.id })
    }

    @Test
    fun `aktywnosc - jeden wpis po zapisie ze zmianami kwoty i platnosci`() {
        h.cashReceipt()
        h.execute(h.command(method = PaymentMethod.CARD, prices = h.price(40_000)))

        val audit = h.audits.single()
        assertEquals(AuditAction.SETTLEMENT_CORRECTED, audit.action)
        assertEquals(setOf("totalGross", "paymentMethod"), audit.changes.map { it.field }.toSet())
        assertEquals(h.savedCorrections.single().id, audit.correlationId)
    }

    @Test
    fun `druga poprawka po pierwszej dziala na nowych dokumentach`() {
        h.cashReceipt()
        h.execute(h.command(prices = h.price(40_000)))
        val afterFirst = h.documents.filter { it.supersededAt == null && it.documentType != DocumentType.CORRECTION }

        h.execute(h.command(method = PaymentMethod.CARD))

        assertTrue(afterFirst.all { it.supersededAt != null })
        assertEquals(49_200, documentsTotal())
        assertEquals(2, h.savedCorrections.size)
    }

    @Test
    fun `poprawka wraca do ceny pierwotnej - dozwolona, sumy dalej sie zgadzaja`() {
        h.cashReceipt()
        h.execute(h.command(prices = h.price(40_000)))
        h.execute(h.command(prices = h.price(50_000)))
        assertEquals(61_500, documentsTotal())
        assertEquals(0, h.cashMovement)
    }

    @Test
    fun `plan liczony drugi raz pod blokada - faktura weszla do sesji po podgladzie`() {
        val (inv, _) = h.invoiceWithDocument(KsefRevenueStatus.QUEUED_RETRY)
        val cmd = h.command(type = DocumentType.INVOICE, method = PaymentMethod.CARD, prices = h.price(40_000))
        assertNull(h.preview(cmd).blockReason)

        inv.ksefStatus = KsefRevenueStatus.SUBMITTED

        val ex = assertThrows<ValidationException> { h.execute(cmd) }
        assertTrue(ex.message!!.contains("czeka na odpowiedź KSeF"))
        assertTrue(h.created.isEmpty())
    }
}
