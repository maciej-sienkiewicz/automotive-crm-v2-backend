package pl.detailing.crm.product.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Relacja wiele-do-wielu WIZYTA ↔ PRODUKT. Czysto informacyjna: „do tej wizyty
 * użyliśmy tego produktu" i nic więcej.
 *
 * ŚWIADOMIE nie ma tu ilości, ceny, snapshotu ani kosztu — produkt nie wpływa na
 * żadną statystykę ani na `totalCost` wizyty (decyzja właściciela produktu). Gdyby
 * kiedyś doszła ewidencja zużycia, jest to naturalne miejsce, ale teraz go nie ma.
 *
 * Brak klucza unikalnego (visit_id, product_id) jest celowy: ten sam produkt może być
 * dopięty do wizyty przez dwie osoby; deduplikację robimy przy odczycie.
 */
@Entity
@Table(
    name = "visit_products",
    indexes = [
        Index(name = "idx_visit_products_visit", columnList = "studio_id, visit_id"),
        Index(name = "idx_visit_products_product", columnList = "studio_id, product_id, created_at")
    ]
)
class VisitProductEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "visit_id", nullable = false, columnDefinition = "uuid")
    val visitId: UUID,

    @Column(name = "product_id", nullable = false, columnDefinition = "uuid")
    val productId: UUID,

    @Column(name = "note", length = 500)
    var note: String?,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_by_name", nullable = false, length = 200)
    val createdByName: String,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
)
