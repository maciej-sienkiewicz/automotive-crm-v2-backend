package pl.detailing.crm.ksef.revenue.xml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.w3c.dom.Document
import org.w3c.dom.Element
import pl.detailing.crm.ksef.revenue.domain.RevenueInvoiceType
import pl.detailing.crm.ksef.revenue.domain.RevenueSource
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceEntity
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceItemEntity
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

class Fa3XmlBuilderTest {

    private val builder = Fa3XmlBuilder()
    private val studioId = UUID.randomUUID()

    private fun invoice(
        buyerNip: String? = "5213017228",
        buyerName: String? = "Firma Testowa Sp. z o.o.",
        invoiceType: RevenueInvoiceType = RevenueInvoiceType.VAT,
        totalGross: Long = 123_000,
        paymentStatus: String = "PENDING",
        paymentForm: String? = "PRZELEW",
        paymentDueDate: LocalDate? = LocalDate.of(2026, 9, 1),
        correctionReason: String? = null
    ) = KsefRevenueInvoiceEntity(
        studioId = studioId,
        source = RevenueSource.CRM,
        invoiceNumber = "FV/2026/0001",
        invoiceType = invoiceType,
        issueDate = LocalDate.of(2026, 8, 13),
        saleDate = LocalDate.of(2026, 8, 12),
        sellerNip = "1234563218",
        sellerName = "Studio Detailingu Blask",
        sellerAddressLine1 = "ul. Polerska 7",
        sellerAddressLine2 = "00-001 Warszawa",
        sellerBankAccount = "PL61 1090 1014 0000 0712 1981 2874",
        buyerNip = buyerNip,
        buyerName = buyerName,
        buyerAddressLine1 = "ul. Kliencka 1",
        buyerAddressLine2 = "00-002 Warszawa",
        totalNet = 100_000,
        totalVat = 23_000,
        totalGross = totalGross,
        paymentForm = paymentForm,
        paymentStatus = paymentStatus,
        paymentDueDate = paymentDueDate,
        correctionReason = correctionReason
    )

    private fun item(
        line: Int = 1,
        name: String = "Korekta lakieru",
        net: Long = 100_000,
        vat: Long = 23_000,
        rate: String = "23"
    ) = KsefRevenueInvoiceItemEntity(
        invoiceId = UUID.randomUUID(),
        lineNumber = line,
        name = name,
        unit = "usł.",
        quantity = BigDecimal.ONE,
        unitPriceNet = net,
        netValue = net,
        vatValue = vat,
        grossValue = net + vat,
        vatRate = rate
    )

    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))

    private fun Document.single(tag: String): Element? {
        val nodes = getElementsByTagNameNS(Fa3XmlBuilder.FA3_NAMESPACE, tag)
        return if (nodes.length > 0) nodes.item(0) as Element else null
    }

    private fun Document.text(tag: String): String? = single(tag)?.textContent

    // ── Struktura podstawowa ───────────────────────────────────────────────────

    @Test
    fun `generuje poprawna strukture FA3 dla faktury B2B`() {
        val xml = builder.build(invoice(), listOf(item()))
        val doc = parse(xml)

        assertEquals(Fa3XmlBuilder.FA3_NAMESPACE, doc.documentElement.namespaceURI)
        assertEquals("Faktura", doc.documentElement.localName)

        val kodFormularza = doc.single("KodFormularza")!!
        assertEquals("FA (3)", kodFormularza.getAttribute("kodSystemowy"))
        assertEquals("1-0E", kodFormularza.getAttribute("wersjaSchemy"))
        assertEquals("FA", kodFormularza.textContent)
        assertEquals("3", doc.text("WariantFormularza"))

        assertEquals("1234563218", (doc.single("Podmiot1")!!.getElementsByTagNameNS(
            Fa3XmlBuilder.FA3_NAMESPACE, "NIP").item(0)).textContent)
        assertEquals("2026-08-13", doc.text("P_1"))
        assertEquals("FV/2026/0001", doc.text("P_2"))
        assertEquals("2026-08-12", doc.text("P_6"))
        assertEquals("VAT", doc.text("RodzajFaktury"))
        assertEquals("1230.00", doc.text("P_15"))
    }

    @Test
    fun `agreguje podstawy i VAT per stawka`() {
        val items = listOf(
            item(line = 1, net = 100_000, vat = 23_000, rate = "23"),
            item(line = 2, name = "Pranie tapicerki", net = 20_000, vat = 4_600, rate = "23"),
            item(line = 3, name = "Usługa 8%", net = 10_000, vat = 800, rate = "8")
        )
        val inv = invoice(totalGross = 158_400)
        val doc = parse(builder.build(inv, items))

        assertEquals("1200.00", doc.text("P_13_1"))   // 23%: 1000 + 200 netto
        assertEquals("276.00", doc.text("P_14_1"))    // 23%: 230 + 46 VAT
        assertEquals("100.00", doc.text("P_13_2"))    // 8% netto
        assertEquals("8.00", doc.text("P_14_2"))      // 8% VAT
        assertNull(doc.single("P_13_3"))              // brak stawki 5%
        assertEquals(3, doc.getElementsByTagNameNS(Fa3XmlBuilder.FA3_NAMESPACE, "FaWiersz").length)
    }

    // ── B2C ────────────────────────────────────────────────────────────────────

    @Test
    fun `nabywca bez NIP dostaje BrakID (B2C)`() {
        val doc = parse(builder.build(invoice(buyerNip = null, buyerName = "Jan Kowalski"), listOf(item())))
        assertEquals("1", doc.text("BrakID"))
        assertEquals(
            0,
            (doc.single("Podmiot2")!!).getElementsByTagNameNS(Fa3XmlBuilder.FA3_NAMESPACE, "NIP").length
        )
    }

    // ── Zwolnienie ─────────────────────────────────────────────────────────────

    @Test
    fun `stawka zw wymaga podstawy prawnej`() {
        val exemptItem = item(net = 50_000, vat = 0, rate = "zw")
        assertThrows<IllegalArgumentException> {
            builder.build(invoice(), listOf(exemptItem))
        }
        val doc = parse(
            builder.build(invoice(), listOf(exemptItem), exemptionLegalBasis = "art. 113 ust. 1 ustawy o VAT")
        )
        assertEquals("1", doc.text("P_19"))
        assertEquals("art. 113 ust. 1 ustawy o VAT", doc.text("P_19A"))
        assertEquals("500.00", doc.text("P_13_7"))
        assertNull(doc.single("P_19N"))
    }

    @Test
    fun `bez zwolnienia emitowany jest P_19N`() {
        val doc = parse(builder.build(invoice(), listOf(item())))
        assertEquals("1", doc.text("P_19N"))
        assertNull(doc.single("P_19"))
    }

    // ── Korekta ────────────────────────────────────────────────────────────────

    @Test
    fun `korekta zawiera DaneFaKorygowanej i przyczyne`() {
        val correction = invoice(
            invoiceType = RevenueInvoiceType.KOR,
            correctionReason = "Rezygnacja z usługi",
            totalGross = -123_000
        )
        val negItem = item(net = -100_000, vat = -23_000)
        val xml = builder.build(
            correction, listOf(negItem),
            correctedInvoice = CorrectedInvoiceRef(
                issueDate = LocalDate.of(2026, 8, 1),
                invoiceNumber = "FV/2026/0001",
                ksefNumber = "1234563218-20260801-ABCDEF123456-01"
            )
        )
        val doc = parse(xml)

        assertEquals("KOR", doc.text("RodzajFaktury"))
        assertEquals("Rezygnacja z usługi", doc.text("PrzyczynaKorekty"))
        assertEquals("2026-08-01", doc.text("DataWystFaKorygowanej"))
        assertEquals("FV/2026/0001", doc.text("NrFaKorygowanej"))
        assertEquals("1234563218-20260801-ABCDEF123456-01", doc.text("NrKSeF"))
        assertEquals("-1230.00", doc.text("P_15"))
        assertEquals("-1000.00", doc.text("P_13_1"))
    }

    @Test
    fun `korekta bez danych faktury korygowanej jest odrzucana`() {
        assertThrows<IllegalArgumentException> {
            builder.build(invoice(invoiceType = RevenueInvoiceType.KOR), listOf(item()))
        }
    }

    // ── Płatność ───────────────────────────────────────────────────────────────

    @Test
    fun `platnosc oczekujaca ma termin i forme platnosci`() {
        val doc = parse(builder.build(invoice(), listOf(item())))
        assertEquals("2026-09-01", doc.text("Termin"))
        assertEquals("6", doc.text("FormaPlatnosci"))  // przelew
        assertEquals("PL61109010140000071219812874", doc.text("NrRB"))
        assertNull(doc.single("Zaplacono"))
    }

    @Test
    fun `faktura oplacona ma znacznik Zaplacono`() {
        val paid = invoice(paymentStatus = "PAID", paymentForm = "GOTOWKA", paymentDueDate = null)
        val doc = parse(builder.build(paid, listOf(item())))
        assertEquals("1", doc.text("Zaplacono"))
        assertEquals("1", doc.text("FormaPlatnosci"))  // gotówka
        assertNull(doc.single("TerminPlatnosci"))
    }

    // ── Walidacja ──────────────────────────────────────────────────────────────

    @Test
    fun `brak pozycji lub danych sprzedawcy jest odrzucany`() {
        assertThrows<IllegalArgumentException> { builder.build(invoice(), emptyList()) }

        val noSellerNip = KsefRevenueInvoiceEntity(
            studioId = studioId,
            invoiceNumber = "FV/2026/0002",
            issueDate = LocalDate.of(2026, 8, 13),
            sellerNip = null,
            sellerName = "Studio"
        )
        assertThrows<IllegalArgumentException> { builder.build(noSellerNip, listOf(item())) }
    }

    @Test
    fun `pozycja wpisana brutto emituje P_9B i P_11A (metoda w stu)`() {
        val grossItem = KsefRevenueInvoiceItemEntity(
            invoiceId = UUID.randomUUID(),
            lineNumber = 1,
            name = "Detailing kompletny",
            quantity = BigDecimal.ONE,
            unitPriceNet = 40_650,
            unitPriceGross = 50_000,
            priceMode = pl.detailing.crm.ksef.revenue.domain.PriceMode.GROSS,
            netValue = 40_650,
            vatValue = 9_350,
            grossValue = 50_000,
            vatRate = "23"
        )
        val doc = parse(builder.build(invoice(totalGross = 50_000), listOf(grossItem)))

        assertEquals("500.00", doc.text("P_9B"))
        assertEquals("500.00", doc.text("P_11A"))
        assertNull(doc.single("P_9A"))
        assertNull(doc.single("P_11"))
        assertEquals("406.50", doc.text("P_13_1"))
        assertEquals("93.50", doc.text("P_14_1"))
    }

    @Test
    fun `znaki specjalne w nazwach sa poprawnie escapowane`() {
        val tricky = item(name = "Powłoka <ceramiczna> & \"wosk\"")
        val doc = parse(builder.build(invoice(), listOf(tricky)))
        assertEquals("Powłoka <ceramiczna> & \"wosk\"", doc.text("P_7"))
    }
}
