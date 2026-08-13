package pl.detailing.crm.ksef.revenue.xml

import org.springframework.stereotype.Component
import pl.detailing.crm.ksef.domain.PaymentForm
import pl.detailing.crm.ksef.revenue.domain.PriceMode
import pl.detailing.crm.ksef.revenue.domain.RevenueInvoiceType
import pl.detailing.crm.ksef.revenue.domain.VatRate
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceEntity
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceItemEntity
import java.io.StringWriter
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

/**
 * Dane faktury korygowanej — sekcja DaneFaKorygowanej w FA_KOR.
 * Wymagane, gdy budowany dokument to korekta (RodzajFaktury=KOR).
 */
data class CorrectedInvoiceRef(
    val issueDate: LocalDate,
    val invoiceNumber: String,
    val ksefNumber: String
)

/**
 * Generator XML faktury ustrukturyzowanej FA(3) — schemat opublikowany przez MF
 * (namespace http://crd.gov.pl/wzor/2025/06/25/13775/, kod systemowy "FA (3)",
 * wersja schemy 1-0E).
 *
 * Zakres wspierany (świadomie dopasowany do sprzedaży usług detailingowych):
 * - faktura VAT i korekta (KOR) metodą różnicową (wiersze i agregaty niosą różnicę,
 *   przy korekcie do zera — wartości ujemne),
 * - nabywca B2B (NIP) i B2C (BrakID=1),
 * - stawki VAT: 23, 8, 5, 0 (krajowe) oraz zw (wymaga podstawy zwolnienia P_19A),
 * - płatność: zapłacono / termin płatności, forma płatności, rachunek bankowy.
 *
 * Kwoty wejściowe w groszach (Long) — serializowane do formatu dziesiętnego
 * z dwoma miejscami (np. 13102.43). Kolejność elementów zgodna z sekwencjami XSD.
 */
@Component
class Fa3XmlBuilder {

    companion object {
        const val FA3_NAMESPACE = "http://crd.gov.pl/wzor/2025/06/25/13775/"
        const val SYSTEM_CODE = "FA (3)"
        const val SCHEMA_VERSION = "1-0E"
        const val SYSTEM_INFO = "Detailing CRM"

        private val DATE = DateTimeFormatter.ISO_LOCAL_DATE
    }

    fun build(
        invoice: KsefRevenueInvoiceEntity,
        items: List<KsefRevenueInvoiceItemEntity>,
        correctedInvoice: CorrectedInvoiceRef? = null,
        exemptionLegalBasis: String? = null,
        generatedAt: Instant = Instant.now()
    ): String {
        require(items.isNotEmpty()) { "Faktura musi mieć co najmniej jedną pozycję" }
        require(!invoice.sellerNip.isNullOrBlank()) { "NIP sprzedawcy jest wymagany" }
        require(!invoice.sellerName.isNullOrBlank()) { "Nazwa sprzedawcy jest wymagana" }
        if (invoice.invoiceType == RevenueInvoiceType.KOR) {
            requireNotNull(correctedInvoice) { "Korekta wymaga danych faktury korygowanej (DaneFaKorygowanej)" }
        }
        val hasExemptRows = items.any { VatRate.fromCode(it.vatRate) == VatRate.ZW }
        if (hasExemptRows) {
            require(!exemptionLegalBasis.isNullOrBlank()) {
                "Faktura ze stawką 'zw' wymaga podstawy prawnej zwolnienia (P_19A)"
            }
        }

        val out = StringWriter()
        val w = XMLOutputFactory.newFactory().createXMLStreamWriter(out)

        w.writeStartDocument("utf-8", "1.0")
        w.writeStartElement("Faktura")
        w.writeDefaultNamespace(FA3_NAMESPACE)

        writeHeader(w, generatedAt)
        writeSeller(w, invoice)
        writeBuyer(w, invoice)
        writeFa(w, invoice, items, correctedInvoice, exemptionLegalBasis, hasExemptRows)

        w.writeEndElement()
        w.writeEndDocument()
        w.flush()
        w.close()
        return out.toString()
    }

    // ── Naglowek ───────────────────────────────────────────────────────────────

    private fun writeHeader(w: XMLStreamWriter, generatedAt: Instant) {
        w.writeStartElement("Naglowek")
        w.writeStartElement("KodFormularza")
        w.writeAttribute("kodSystemowy", SYSTEM_CODE)
        w.writeAttribute("wersjaSchemy", SCHEMA_VERSION)
        w.writeCharacters("FA")
        w.writeEndElement()
        el(w, "WariantFormularza", "3")
        el(w, "DataWytworzeniaFa", generatedAt.toString())
        el(w, "SystemInfo", SYSTEM_INFO)
        w.writeEndElement()
    }

    // ── Podmiot1 (sprzedawca) ──────────────────────────────────────────────────

    private fun writeSeller(w: XMLStreamWriter, invoice: KsefRevenueInvoiceEntity) {
        w.writeStartElement("Podmiot1")
        w.writeStartElement("DaneIdentyfikacyjne")
        el(w, "NIP", normalizeNip(invoice.sellerNip!!))
        el(w, "Nazwa", invoice.sellerName!!)
        w.writeEndElement()
        writeAddress(w, invoice.sellerCountryCode, invoice.sellerAddressLine1, invoice.sellerAddressLine2)
        w.writeEndElement()
    }

    // ── Podmiot2 (nabywca) ─────────────────────────────────────────────────────

    private fun writeBuyer(w: XMLStreamWriter, invoice: KsefRevenueInvoiceEntity) {
        w.writeStartElement("Podmiot2")
        w.writeStartElement("DaneIdentyfikacyjne")
        val buyerNip = invoice.buyerNip?.takeIf { it.isNotBlank() }
        if (buyerNip != null) {
            el(w, "NIP", normalizeNip(buyerNip))
        } else {
            // Konsument (B2C) — brak identyfikatora podatkowego
            el(w, "BrakID", "1")
        }
        invoice.buyerName?.takeIf { it.isNotBlank() }?.let { el(w, "Nazwa", it) }
        w.writeEndElement()
        if (!invoice.buyerAddressLine1.isNullOrBlank() || !invoice.buyerAddressLine2.isNullOrBlank()) {
            writeAddress(w, invoice.buyerCountryCode, invoice.buyerAddressLine1, invoice.buyerAddressLine2)
        }
        invoice.buyerEmail?.takeIf { it.isNotBlank() }?.let {
            w.writeStartElement("DaneKontaktowe")
            el(w, "Email", it)
            w.writeEndElement()
        }
        w.writeEndElement()
    }

    private fun writeAddress(w: XMLStreamWriter, countryCode: String?, line1: String?, line2: String?) {
        w.writeStartElement("Adres")
        el(w, "KodKraju", countryCode?.takeIf { it.isNotBlank() } ?: "PL")
        el(w, "AdresL1", line1?.takeIf { it.isNotBlank() } ?: "-")
        line2?.takeIf { it.isNotBlank() }?.let { el(w, "AdresL2", it) }
        w.writeEndElement()
    }

    // ── Fa ─────────────────────────────────────────────────────────────────────

    private fun writeFa(
        w: XMLStreamWriter,
        invoice: KsefRevenueInvoiceEntity,
        items: List<KsefRevenueInvoiceItemEntity>,
        correctedInvoice: CorrectedInvoiceRef?,
        exemptionLegalBasis: String?,
        hasExemptRows: Boolean
    ) {
        w.writeStartElement("Fa")
        el(w, "KodWaluty", invoice.currency)
        el(w, "P_1", invoice.issueDate.format(DATE))
        el(w, "P_2", invoice.invoiceNumber)
        invoice.saleDate?.let { el(w, "P_6", it.format(DATE)) }

        writeVatAggregates(w, items)
        el(w, "P_15", grosz(invoice.totalGross))

        writeAnnotations(w, hasExemptRows, exemptionLegalBasis)

        el(w, "RodzajFaktury", invoice.invoiceType.name)

        if (invoice.invoiceType == RevenueInvoiceType.KOR && correctedInvoice != null) {
            invoice.correctionReason?.takeIf { it.isNotBlank() }?.let { el(w, "PrzyczynaKorekty", it) }
            w.writeStartElement("DaneFaKorygowanej")
            el(w, "DataWystFaKorygowanej", correctedInvoice.issueDate.format(DATE))
            el(w, "NrFaKorygowanej", correctedInvoice.invoiceNumber)
            el(w, "NrKSeF", correctedInvoice.ksefNumber)
            w.writeEndElement()
        }

        items.forEach { writeLine(w, it) }
        writePayment(w, invoice)

        w.writeEndElement()
    }

    /**
     * Agregaty podstaw i podatku per stawka — pola P_13_x/P_14_x sekcji Fa.
     * Emitowane tylko dla stawek faktycznie występujących na fakturze:
     *   23% → P_13_1/P_14_1, 8% → P_13_2/P_14_2, 5% → P_13_3/P_14_3,
     *   0% (krajowe) → P_13_6_1, zw → P_13_7 (stawki bez podatku nie mają pola P_14).
     */
    private fun writeVatAggregates(w: XMLStreamWriter, items: List<KsefRevenueInvoiceItemEntity>) {
        val byRate = items.groupBy { VatRate.fromCode(it.vatRate) }

        fun sums(rate: VatRate): Pair<Long, Long>? = byRate[rate]?.let { rows ->
            rows.sumOf { it.netValue } to rows.sumOf { it.vatValue }
        }

        sums(VatRate.RATE_23)?.let { (net, vat) ->
            el(w, "P_13_1", grosz(net)); el(w, "P_14_1", grosz(vat))
        }
        sums(VatRate.RATE_8)?.let { (net, vat) ->
            el(w, "P_13_2", grosz(net)); el(w, "P_14_2", grosz(vat))
        }
        sums(VatRate.RATE_5)?.let { (net, vat) ->
            el(w, "P_13_3", grosz(net)); el(w, "P_14_3", grosz(vat))
        }
        sums(VatRate.RATE_0)?.let { (net, _) ->
            el(w, "P_13_6_1", grosz(net))
        }
        sums(VatRate.ZW)?.let { (net, _) ->
            el(w, "P_13_7", grosz(net))
        }
    }

    /**
     * Sekcja Adnotacje. Wartość 2 = "nie dotyczy" (metoda kasowa, samofakturowanie,
     * odwrotne obciążenie, MPP); zagnieżdżone znaczniki N = 1 potwierdzają brak
     * zwolnienia / nowych środków transportu / procedury marży. Przy sprzedaży zw
     * emitowane jest P_19=1 z podstawą prawną w P_19A.
     */
    private fun writeAnnotations(w: XMLStreamWriter, hasExemptRows: Boolean, exemptionLegalBasis: String?) {
        w.writeStartElement("Adnotacje")
        el(w, "P_16", "2")
        el(w, "P_17", "2")
        el(w, "P_18", "2")
        el(w, "P_18A", "2")
        w.writeStartElement("Zwolnienie")
        if (hasExemptRows) {
            el(w, "P_19", "1")
            el(w, "P_19A", exemptionLegalBasis!!)
        } else {
            el(w, "P_19N", "1")
        }
        w.writeEndElement()
        w.writeStartElement("NoweSrodkiTransportu")
        el(w, "P_22N", "1")
        w.writeEndElement()
        el(w, "P_23", "2")
        w.writeStartElement("PMarzy")
        el(w, "P_PMarzyN", "1")
        w.writeEndElement()
        w.writeEndElement()
    }

    /**
     * Pozycja NET: cena/wartość netto (P_9A/P_11) — metoda „od netto".
     * Pozycja GROSS: cena/wartość brutto (P_9B/P_11A) — metoda „w stu"
     * (art. 106e ust. 7 ustawy o VAT); brutto dokładnie jak wpisał użytkownik.
     */
    private fun writeLine(w: XMLStreamWriter, item: KsefRevenueInvoiceItemEntity) {
        w.writeStartElement("FaWiersz")
        el(w, "NrWierszaFa", item.lineNumber.toString())
        el(w, "UU_ID", item.id.toString())
        el(w, "P_7", item.name)
        item.unit?.takeIf { it.isNotBlank() }?.let { el(w, "P_8A", it) }
        el(w, "P_8B", item.quantity.stripTrailingZeros().toPlainString())
        if (item.priceMode == PriceMode.GROSS && item.unitPriceGross != null) {
            el(w, "P_9B", grosz(item.unitPriceGross))
            el(w, "P_11A", grosz(item.grossValue))
        } else {
            el(w, "P_9A", grosz(item.unitPriceNet))
            el(w, "P_11", grosz(item.netValue))
        }
        el(w, "P_12", item.vatRate)
        w.writeEndElement()
    }

    private fun writePayment(w: XMLStreamWriter, invoice: KsefRevenueInvoiceEntity) {
        val paymentForm = invoice.paymentForm?.let { runCatching { PaymentForm.valueOf(it) }.getOrNull() }
        val isPaid = invoice.paymentStatus == "PAID"
        val hasAnything = isPaid || paymentForm != null ||
            invoice.paymentDueDate != null || !invoice.sellerBankAccount.isNullOrBlank()
        if (!hasAnything) return

        w.writeStartElement("Platnosc")
        if (isPaid) {
            el(w, "Zaplacono", "1")
            val paidDate = invoice.paidAt
                ?.atZone(java.time.ZoneId.of("Europe/Warsaw"))?.toLocalDate()
                ?: invoice.issueDate
            el(w, "DataZaplaty", paidDate.format(DATE))
        } else {
            invoice.paymentDueDate?.let {
                w.writeStartElement("TerminPlatnosci")
                el(w, "Termin", it.format(DATE))
                w.writeEndElement()
            }
        }
        paymentForm?.let { el(w, "FormaPlatnosci", it.ksefCode) }
        invoice.sellerBankAccount?.takeIf { it.isNotBlank() }?.let {
            w.writeStartElement("RachunekBankowy")
            el(w, "NrRB", it.replace(" ", ""))
            w.writeEndElement()
        }
        w.writeEndElement()
    }

    // ── Utils ──────────────────────────────────────────────────────────────────

    private fun el(w: XMLStreamWriter, name: String, value: String) {
        w.writeStartElement(name)
        w.writeCharacters(value)
        w.writeEndElement()
    }

    /** Grosze (Long) → format dziesiętny FA(3) z dwoma miejscami, np. 13102.43. */
    private fun grosz(amount: Long): String =
        BigDecimal(amount).movePointLeft(2).setScale(2).toPlainString()

    private fun normalizeNip(nip: String): String = nip.replace(Regex("[^0-9]"), "")
}
