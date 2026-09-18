package pl.detailing.crm.ksef.revenue.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Wizualizacja faktury musi nieść komplet danych z art. 106e ust. 1 ustawy o VAT.
 * Brak któregokolwiek z nich to nie usterka wyglądu, tylko dokument, którego nabywca
 * nie może zaksięgować — dlatego każdy punkt ma tu własne asercje.
 */
class InvoicePdfRendererTest {

    private val renderer = InvoicePdfRenderer()

    private fun line(
        no: Int,
        name: String,
        qty: String = "1",
        net: Long = 100_00,
        vat: Long = 23_00,
        rate: String = "23"
    ) = InvoiceLine(
        lineNumber = no,
        name = name,
        unit = "szt.",
        quantity = BigDecimal(qty),
        unitPriceNet = net,
        netValue = net,
        vatValue = vat,
        grossValue = net + vat,
        vatRate = rate
    )

    private fun data(
        lines: List<InvoiceLine> = listOf(line(1, "Korekta lakieru dwuetapowa", net = 1_800_00, vat = 414_00)),
        ksefNumber: String? = "1234567890-20260918-ABCDEF123456-7A",
        correction: Boolean = false,
        qr: ByteArray? = null
    ): InvoicePdfData {
        val net = lines.sumOf { it.netValue }
        val vat = lines.sumOf { it.vatValue }
        return InvoicePdfData(
            title = if (correction) "FAKTURA KORYGUJĄCA" else "FAKTURA",
            invoiceNumber = "FV/2026/09/0001",
            issueDate = "18.09.2026",
            saleDate = "15.09.2026",
            paymentDueDate = "02.10.2026",
            seller = InvoiceParty(
                "Detail Studio Kraków sp. z o.o.", "ul. Zabłocie 23/4", "30-701 Kraków", "6771234567", "PL"
            ),
            buyer = InvoiceParty(
                "Zażółć Gęślą Jaźń sp. j.", "ul. Kwiatowa 5", "00-001 Warszawa", "5252525252", "PL"
            ),
            lines = lines,
            vatBuckets = lines.groupBy { it.vatRate }.map { (rate, group) ->
                InvoiceVatBucket(rate, group.sumOf { it.netValue }, group.sumOf { it.vatValue }, group.sumOf { it.grossValue })
            },
            totalNet = net,
            totalVat = vat,
            totalGross = net + vat,
            currency = "PLN",
            totalInWords = pl.detailing.crm.shared.AmountInWords.format(net + vat),
            paymentFormLabel = "Przelew",
            paymentStatusLabel = "Oczekuje na zapłatę",
            bankAccount = "PL61109010140000071219812874",
            correctedInvoiceNumber = if (correction) "FV/2026/08/0042" else null,
            correctionReason = if (correction) "Błędna stawka VAT" else null,
            ksefNumber = ksefNumber,
            verificationQrPng = qr,
            draftNotice = if (ksefNumber == null) {
                "Dokument nie ma jeszcze numeru KSeF. Wydruk jest podglądem roboczym i nie stanowi faktury ustrukturyzowanej."
            } else {
                null
            },
            providerName = "Detail Studio Kraków sp. z o.o.",
            providerAddress = "ul. Zabłocie 23/4, 30-701 Kraków",
            contactLine = "+48 600 100 200, kontakt@detailstudio.pl",
            logoPng = null
        )
    }

    private fun textOf(bytes: ByteArray): String =
        Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

    private fun pagesOf(bytes: ByteArray): Int = Loader.loadPDF(bytes).use { it.numberOfPages }

    @Test
    fun `niesie komplet danych wymaganych przez art 106e`() {
        val text = textOf(renderer.render(data()))

        assertTrue(text.contains("FAKTURA"), "brak tytułu dokumentu")
        assertTrue(text.contains("FV/2026/09/0001"), "pkt 2: numer faktury")
        assertTrue(text.contains("18.09.2026"), "pkt 1: data wystawienia")
        assertTrue(text.contains("15.09.2026"), "pkt 6: data sprzedaży")
        assertTrue(text.contains("Detail Studio Kraków"), "pkt 3: nazwa sprzedawcy")
        assertTrue(text.contains("Zażółć Gęślą Jaźń"), "pkt 3: nazwa nabywcy")
        assertTrue(text.contains("ul. Zabłocie 23/4"), "pkt 3: adres sprzedawcy")
        assertTrue(text.contains("ul. Kwiatowa 5"), "pkt 3: adres nabywcy")
        assertTrue(text.contains("6771234567"), "pkt 4: NIP sprzedawcy")
        assertTrue(text.contains("5252525252"), "pkt 5: NIP nabywcy")
        assertTrue(text.contains("Korekta lakieru dwuetapowa"), "pkt 7: nazwa usługi")
        assertTrue(text.contains("szt."), "pkt 8: miara")
        assertTrue(text.contains("1 800,00"), "pkt 9/11: cena i wartość netto")
        assertTrue(text.contains("414,00"), "pkt 14: kwota podatku")
        assertTrue(text.contains("2 214,00"), "pkt 15: kwota należności ogółem")
        assertTrue(text.contains("23%"), "pkt 12: stawka podatku")
        assertTrue(text.contains("PODSUMOWANIE WEDŁUG STAWEK"), "pkt 13-14: podział na stawki")
        assertTrue(text.contains("dwa tysiące dwieście czternaście złotych"), "kwota słownie")
    }

    @Test
    fun `numer KSeF jest na dokumencie`() {
        val text = textOf(renderer.render(data()))
        assertTrue(text.contains("KRAJOWY SYSTEM E-FAKTUR"), "brak bloku KSeF: $text")
        assertTrue(text.contains("1234567890-20260918-ABCDEF123456-7A"), "brak numeru KSeF")
    }

    /**
     * Wydruk sprzed przyjęcia przez KSeF nie jest jeszcze fakturą ustrukturyzowaną.
     * Milczenie o tym wprowadzałoby odbiorcę w błąd.
     */
    @Test
    fun `dokument bez numeru KSeF mówi o tym wprost`() {
        val text = textOf(renderer.render(data(ksefNumber = null)))
        assertTrue(text.contains("podglądem roboczym"), "brak ostrzeżenia o braku numeru KSeF: $text")
    }

    /** Korekta musi wskazać fakturę pierwotną i przyczynę — art. 106j ust. 2. */
    /**
     * Faktura pobrana z KSeF bez treści XML nie ma zapisanych pozycji. Pusta tabela
     * czytałaby się jak faktura bez pozycji — dokument ma powiedzieć, czego brakuje.
     */
    @Test
    fun `faktura bez zapisanych pozycji mówi o tym wprost`() {
        val text = textOf(renderer.render(data(lines = emptyList())))
        assertTrue(text.contains("Pozycje nie są dostępne"), "brak informacji o braku pozycji: $text")
        assertTrue(text.contains("DO ZAPŁATY"), "sumy z metadanych muszą zostać na dokumencie")
    }

    @Test
    fun `korekta wskazuje fakturę pierwotną i przyczynę`() {
        val text = textOf(renderer.render(data(correction = true)))

        assertTrue(text.contains("FAKTURA KORYGUJĄCA"), "brak tytułu korekty")
        assertTrue(text.contains("FV/2026/08/0042"), "brak numeru faktury pierwotnej")
        assertTrue(text.contains("Błędna stawka VAT"), "brak przyczyny korekty")
    }

    @Test
    fun `podsumowanie rozbija kwoty na stawki`() {
        val text = textOf(
            renderer.render(
                data(
                    lines = listOf(
                        line(1, "Powłoka ceramiczna", net = 1_000_00, vat = 230_00, rate = "23"),
                        line(2, "Usługa zwolniona", net = 200_00, vat = 0, rate = "zw")
                    )
                )
            )
        )

        assertTrue(text.contains("23%"), "brak stawki podstawowej")
        assertTrue(text.contains("zw."), "brak stawki zwolnionej")
        assertTrue(text.contains("1 430,00"), "brak kwoty należności ogółem: $text")
    }

    @Test
    fun `długa lista pozycji przechodzi na kolejną stronę z nagłówkiem tabeli`() {
        val many = (1..40).map { line(it, "Pozycja numer $it z dłuższą nazwą usługi detailingowej") }
        val bytes = renderer.render(data(lines = many))

        assertTrue(pagesOf(bytes) > 1, "40 pozycji nie mieści się na jednej stronie")
        val text = textOf(bytes)
        assertTrue(text.contains("Pozycja numer 40"), "ostatnia pozycja zgubiona przy łamaniu stron")
        // Nagłówek kolumn musi się powtórzyć: kwoty bez podpisów są nie do odczytania.
        assertTrue(
            Regex("NAZWA TOWARU LUB USŁUGI").findAll(text).count() > 1,
            "nagłówek tabeli nie powtórzył się na kolejnej stronie"
        )
        assertTrue(text.contains("DO ZAPŁATY"), "podsumowanie musi przetrwać łamanie stron")
    }

    @Test
    fun `kod QR trafia na dokument, gdy jest dostępny`() {
        val qr = QrCodeImageFactory().png("https://ksef.mf.gov.pl/invoice/6771234567/18-09-2026/abc")
        assertTrue(qr != null && qr.isNotEmpty(), "generator kodu QR nie zwrócił obrazu")

        val bytes = renderer.render(data(qr = qr))
        assertTrue(textOf(bytes).contains("Zeskanuj kod"), "brak podpisu pod kodem QR")
        // Sam obraz: dokument z kodem musi być wyraźnie większy niż bez niego.
        assertTrue(bytes.size > renderer.render(data()).size, "kod QR nie został osadzony")
    }
}
