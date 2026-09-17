package pl.detailing.crm.product.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Ocena produktu (1–5). Dokładnie JEDNA na produkt na studio — unikalność wymusza
 * indeks `uq_product_ratings (studio_id, product_id)`.
 *
 * Ocena należy do STUDIA, nie do osoby: w trzyosobowym warsztacie „nasza opinia o tym
 * preparacie" jest jedna i wspólna. Zapisujemy jednak, kto ostatnio ustawił i kiedy —
 * żeby nadpisanie cudzej oceny nie było anonimowe i było wiadomo, kogo zapytać.
 */
@Entity
@Table(
    name = "product_ratings",
    indexes = [Index(name = "idx_product_ratings_product", columnList = "studio_id, product_id")]
)
class ProductRatingEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "product_id", nullable = false, columnDefinition = "uuid")
    val productId: UUID,

    @Column(name = "rating", nullable = false)
    var rating: Int,

    @Column(name = "justification", length = 500)
    var justification: String?,

    @Column(name = "rated_by", nullable = false, columnDefinition = "uuid")
    var ratedBy: UUID,

    @Column(name = "rated_by_name", nullable = false, length = 200)
    var ratedByName: String,

    @Column(name = "rated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var ratedAt: Instant = Instant.now()
) {
    init {
        require(rating in 1..5) { "Ocena produktu musi mieścić się w 1..5" }
    }
}
