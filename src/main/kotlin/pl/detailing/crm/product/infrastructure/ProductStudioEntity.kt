package pl.detailing.crm.product.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Nakładka studia na globalny produkt — WSZYSTKO tu jest prywatne dla tenanta.
 *
 * Cena jednostkowa jest jedynym polem pieniężnym w całym module i jest wyłącznie
 * informacyjna (moduł niczego nie liczy). Mimo to obowiązuje reguła brutto z
 * CLAUDE.md §1: zapisujemy kwotę, którą wpisał człowiek, kierunek wpisania
 * ([priceEnteredAs]) ORAZ drugą stronę policzoną RAZ przy zapisie — nigdy
 * odtwarzaną na odczycie. Para (netto, brutto, kierunek, vat) jest albo kompletna,
 * albo pusta; niezmiennik pilnuje tego w [init] i CHECK w V100.
 */
@Entity
@Table(
    name = "product_studio",
    indexes = [
        Index(name = "idx_product_studio_studio", columnList = "studio_id"),
        Index(name = "idx_product_studio_product", columnList = "product_id")
    ]
)
class ProductStudioEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "product_id", nullable = false, columnDefinition = "uuid")
    val productId: UUID,

    // Cena jednostkowa (informacyjna). null-e = studio nie podało ceny.
    @Column(name = "unit_price_net_cents")
    var unitPriceNetCents: Long?,

    @Column(name = "unit_price_gross_cents")
    var unitPriceGrossCents: Long?,

    @Column(name = "price_entered_as", length = 5)
    var priceEnteredAs: String?,   // NET | GROSS — źródło prawdy pary cenowej

    @Column(name = "vat_rate")
    var vatRate: Int?,             // 23 | 8 | 5 | 0 | -1 (zwolniony)


    @Column(name = "internal_name", length = 200)
    var internalName: String?,

    @Column(name = "internal_note", columnDefinition = "TEXT")
    var internalNote: String?,

    @Column(name = "is_favourite", nullable = false)
    var isFavourite: Boolean = false,

    @Column(name = "is_hidden", nullable = false)
    var isHidden: Boolean = false,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_by", nullable = false, columnDefinition = "uuid")
    var updatedBy: UUID,

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    init {
        val filled = listOf(unitPriceNetCents, unitPriceGrossCents, priceEnteredAs, vatRate).count { it != null }
        require(filled == 0 || filled == 4) {
            "Cena jednostkowa musi być kompletna (netto, brutto, kierunek, VAT) albo pusta — " +
                "połowiczna kwota to grosz zgubiony na odczycie (CLAUDE.md §1)."
        }
    }

    val hasPrice: Boolean get() = unitPriceNetCents != null
}
