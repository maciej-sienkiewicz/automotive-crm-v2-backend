package pl.detailing.crm.ksef.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.util.UUID

/**
 * Pojedyncza pozycja faktury kosztowej (wiersz <FaWiersz> z XML KSeF).
 *
 * Powiązana z [KsefInvoiceEntity] przez [invoiceId] (bez relacji JPA — spójnie
 * z resztą modułu pozycje ładowane są jawnie przez repozytorium).
 *
 * Pola odwzorowują schemat FA(2):
 *  name (P_7), unit (P_8A), quantity (P_8B), unitPriceNet (P_9A),
 *  netValue (P_11), grossValue (P_11A), vatRate (P_12)
 */
@Entity
@Table(
    name = "ksef_invoice_items",
    indexes = [
        Index(name = "idx_ksef_invoice_items_invoice_id", columnList = "invoice_id")
    ]
)
class KsefInvoiceItemEntity(

    @Id
    val id: UUID = UUID.randomUUID(),

    /** ID faktury z tabeli ksef_invoices. */
    @Column(name = "invoice_id", nullable = false)
    val invoiceId: UUID,

    /** Numer wiersza na fakturze (NrWierszaFa) — wyznacza kolejność prezentacji. */
    @Column(name = "line_number", nullable = false)
    val lineNumber: Int,

    /** Nazwa towaru lub usługi (P_7). */
    @Column(name = "name", length = 1000)
    val name: String?,

    /** Jednostka miary (P_8A), np. szt., usł., kg. */
    @Column(name = "unit", length = 50)
    val unit: String?,

    /** Ilość (P_8B). */
    @Column(name = "quantity")
    val quantity: Double?,

    /** Cena jednostkowa netto (P_9A). */
    @Column(name = "unit_price_net")
    val unitPriceNet: Double?,

    /** Wartość netto pozycji (P_11). */
    @Column(name = "net_value")
    val netValue: Double?,

    /** Wartość brutto pozycji (P_11A) — wypełniona tylko dla faktur liczonych od brutto. */
    @Column(name = "gross_value")
    val grossValue: Double?,

    /** Stawka VAT (P_12) — wartość ze schematu FA(2), np. "23", "8", "0", "zw", "np", "oo". */
    @Column(name = "vat_rate", length = 10)
    val vatRate: String?
)
