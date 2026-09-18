package pl.detailing.crm.ksef.revenue.pdf

import java.math.BigDecimal

/** Strona transakcji na fakturze: nazwa, adres i NIP — art. 106e ust. 1 pkt 3-5. */
data class InvoiceParty(
    val name: String,
    val addressLine1: String?,
    val addressLine2: String?,
    val nip: String?,
    val countryCode: String?
)

/** Wiersz tabeli pozycji — art. 106e ust. 1 pkt 7-12. */
data class InvoiceLine(
    val lineNumber: Int,
    val name: String,
    val unit: String?,
    val quantity: BigDecimal,
    val unitPriceNet: Long,
    val netValue: Long,
    val vatValue: Long,
    val grossValue: Long,
    val vatRate: String
)

/** Podsumowanie w podziale na stawki — art. 106e ust. 1 pkt 13-14. */
data class InvoiceVatBucket(
    val vatRate: String,
    val net: Long,
    val vat: Long,
    val gross: Long
)

/**
 * Wszystko, co rysuje [InvoicePdfRenderer] — bez zależności od encji i JPA.
 *
 * Kwoty w GROSZACH. Żadna z nich nie jest tu przeliczana: przychodzą z faktury takie,
 * jakie poszły do KSeF, a wizualizacja ma odwzorowywać dane merytoryczne z systemu,
 * nie liczyć ich po raz drugi (CLAUDE.md §1).
 */
data class InvoicePdfData(
    /** „FAKTURA" albo „FAKTURA KORYGUJĄCA". */
    val title: String,
    val invoiceNumber: String,
    val issueDate: String,
    /** Data dokonania dostawy lub wykonania usługi; null, gdy równa dacie wystawienia. */
    val saleDate: String?,
    val paymentDueDate: String?,

    val seller: InvoiceParty,
    val buyer: InvoiceParty,

    val lines: List<InvoiceLine>,
    val vatBuckets: List<InvoiceVatBucket>,
    val totalNet: Long,
    val totalVat: Long,
    val totalGross: Long,
    val currency: String,
    val totalInWords: String,

    val paymentFormLabel: String?,
    val paymentStatusLabel: String?,
    val bankAccount: String?,

    /** Korekta: numer faktury pierwotnej i przyczyna — art. 106j ust. 2. */
    val correctedInvoiceNumber: String?,
    val correctionReason: String?,

    /**
     * Numer KSeF nadany fakturze; null, gdy dokument do KSeF nie dotarł. Null wycisza cały
     * blok KSeF: faktura poza systemem nie wspomina o nim ani słowem.
     */
    val ksefNumber: String?,
    /** Kod QR z adresem weryfikacyjnym KSeF (PNG) — wizualizacja udostępniana poza systemem. */
    val verificationQrPng: ByteArray?,

    val providerName: String,
    val providerAddress: String?,
    val contactLine: String?,
    val logoPng: ByteArray?
)
