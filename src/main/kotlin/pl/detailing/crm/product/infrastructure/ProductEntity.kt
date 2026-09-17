package pl.detailing.crm.product.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.product.domain.PackageDimensions
import pl.detailing.crm.product.domain.ProductSource
import pl.detailing.crm.product.domain.ProductSpec
import pl.detailing.crm.product.domain.Provenance
import pl.detailing.crm.product.domain.UnitOfMeasure
import pl.detailing.crm.product.domain.VerificationLevel
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Globalny wiersz katalogu — WSPÓŁDZIELONY między wszystkimi tenantami.
 *
 * Brak kolumny `studio_id` jest tu jedynym takim wyjątkiem w całej bazie i jest
 * ZAMIERZONY: specyfikacja produktu (nazwa, marka, opakowanie) jest
 * obiektywna i drugie studio skanujące ten sam kod dostaje ją gotową. Dane prywatne
 * studia — cena, notatki, ocena, powiązania z wizytami — leżą w osobnych tabelach,
 * każda z własnym `studio_id`. Każdy przegląd bezpieczeństwa, który tu trafi, ma
 * przeczytać ten komentarz, zanim uzna brak `studio_id` za błąd.
 */
@Entity
@Table(
    name = "products",
    indexes = [
        Index(name = "idx_products_brand", columnList = "brand"),
        Index(name = "idx_products_created_by_studio", columnList = "created_by_studio_id")
    ]
)
class ProductEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    // Tożsamość. GTIN-14 znormalizowany (patrz domena Gtin). Unikalność wymusza
    // indeks częściowy z V100 (uq_products_gtin WHERE gtin IS NOT NULL) — produkty bez
    // kodu deduplikuje uq_products_natural_key.
    @Column(name = "gtin", length = 14)
    var gtin: String?,

    @Column(name = "name", nullable = false, length = 200)
    var name: String,

    @Column(name = "brand", nullable = false, length = 120)
    var brand: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "unit_of_measure", nullable = false, length = 10)
    var unitOfMeasure: UnitOfMeasure,

    @Column(name = "package_size_value", nullable = false, precision = 12, scale = 3)
    var packageSizeValue: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(name = "package_size_unit", nullable = false, length = 10)
    var packageSizeUnit: UnitOfMeasure,

    @Column(name = "package_height_mm")
    var packageHeightMm: Int?,

    @Column(name = "package_width_mm")
    var packageWidthMm: Int?,

    @Column(name = "package_depth_mm")
    var packageDepthMm: Int?,

    @Column(name = "description", columnDefinition = "TEXT")
    var description: String?,

    @Column(name = "image_file_id", length = 500)
    var imageFileId: String?,

    // ── Ślad pochodzenia ──
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    var source: ProductSource,

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_level", nullable = false, length = 20)
    var verificationLevel: VerificationLevel,

    @Column(name = "source_confidence", precision = 4, scale = 3)
    var sourceConfidence: BigDecimal?,

    @Column(name = "source_payload", columnDefinition = "TEXT")
    var sourcePayload: String?,

    @Column(name = "resolved_at", columnDefinition = "timestamp with time zone")
    var resolvedAt: Instant?,

    // Kto fizycznie założył wiersz. Do moderacji i cofania zatruć — NIGDY nie wychodzi
    // na zewnątrz: informacja, czym pracuje konkretne studio, jest wrażliwa.
    @Column(name = "created_by_studio_id", nullable = false, columnDefinition = "uuid")
    val createdByStudioId: UUID,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_by", nullable = false, columnDefinition = "uuid")
    var updatedBy: UUID,

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now(),

    @Column(name = "is_withdrawn", nullable = false)
    var isWithdrawn: Boolean = false,

    @Column(name = "withdrawn_reason", length = 300)
    var withdrawnReason: String? = null
) {
    fun toSpec(): ProductSpec = ProductSpec(
        gtin = gtin,
        name = name,
        brand = brand,
        unitOfMeasure = unitOfMeasure,
        packageSizeValue = packageSizeValue,
        packageSizeUnit = packageSizeUnit,
        dimensions = PackageDimensions(packageHeightMm, packageWidthMm, packageDepthMm),
        description = description,
        imageFileId = imageFileId
    )

    fun provenance(): Provenance = Provenance(
        source = source,
        verificationLevel = verificationLevel,
        confidence = sourceConfidence?.toDouble()
    )
}
