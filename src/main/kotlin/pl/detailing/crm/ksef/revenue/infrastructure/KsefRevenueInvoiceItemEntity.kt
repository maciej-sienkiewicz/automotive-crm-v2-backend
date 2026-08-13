package pl.detailing.crm.ksef.revenue.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import pl.detailing.crm.ksef.revenue.domain.PriceMode
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Pozycja faktury przychodowej (FaWiersz w FA(3)).
 * Kwoty w groszach (Long); ilość jako BigDecimal(12,3) — usługi bywają ułamkowe (rbh).
 */
@Entity
@Table(name = "ksef_revenue_invoice_items")
class KsefRevenueInvoiceItemEntity(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "invoice_id", nullable = false)
    val invoiceId: UUID,

    @Column(name = "line_number", nullable = false)
    val lineNumber: Int,

    /** Nazwa towaru/usługi (P_7). */
    @Column(name = "name", nullable = false, length = 500)
    val name: String,

    /** Jednostka miary (P_8A), np. szt., usł., rbh. */
    @Column(name = "unit", length = 20)
    val unit: String? = null,

    /** Ilość (P_8B). */
    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    val quantity: BigDecimal = BigDecimal.ONE,

    /** Cena jednostkowa netto w groszach (P_9A). Dla [priceMode] GROSS — wartość pochodna. */
    @Column(name = "unit_price_net", nullable = false)
    val unitPriceNet: Long,

    /** Cena jednostkowa brutto w groszach (P_9B) — tylko gdy [priceMode] == GROSS. */
    @Column(name = "unit_price_gross")
    val unitPriceGross: Long? = null,

    /**
     * Które pole wpisał użytkownik: NET → XML metodą netto (P_9A/P_11);
     * GROSS → XML metodą brutto (P_9B/P_11A), brutto dokładnie jak wpisane.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "price_mode", nullable = false, length = 5, columnDefinition = "VARCHAR(5) DEFAULT 'NET'")
    val priceMode: PriceMode = PriceMode.NET,

    /** Wartość netto wiersza w groszach (P_11). */
    @Column(name = "net_value", nullable = false)
    val netValue: Long,

    /** VAT wiersza w groszach. */
    @Column(name = "vat_value", nullable = false)
    val vatValue: Long = 0,

    /** Wartość brutto wiersza w groszach. */
    @Column(name = "gross_value", nullable = false)
    val grossValue: Long,

    /** Stawka VAT jako kod FA(3): 23 | 8 | 5 | 0 | zw (P_12). */
    @Column(name = "vat_rate", nullable = false, length = 5)
    val vatRate: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)
