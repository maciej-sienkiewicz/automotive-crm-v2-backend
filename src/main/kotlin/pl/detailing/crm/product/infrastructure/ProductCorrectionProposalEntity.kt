package pl.detailing.crm.product.infrastructure

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Propozycja korekty globalnego wpisu katalogu. Powstaje, gdy studio próbuje zmienić
 * produkt o poziomie zweryfikowanym (GS1/STUDIO_CONFIRMED/CURATED) — zamiast zapisu
 * in-place, który psułby dane wszystkim tenantom (§2.4 architektury).
 *
 * Panel moderacyjny (przeglądanie i zatwierdzanie) to faza 5; wiersze zbierają się od
 * pierwszego dnia, żeby po włączeniu panelu było na czym pracować. Studio, które chce
 * inną nazwę u siebie od zaraz, ma `product_studio.internal_name`.
 */
@Entity
@Table(
    name = "product_correction_proposals",
    indexes = [Index(name = "idx_product_proposals_status", columnList = "status, created_at")]
)
class ProductCorrectionProposalEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "product_id", nullable = false, columnDefinition = "uuid")
    val productId: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "proposed_fields", nullable = false, columnDefinition = "TEXT")
    val proposedFields: String,   // JSON zaproponowanych pól

    @Column(name = "reason", length = 500)
    val reason: String?,

    @Column(name = "status", nullable = false, length = 20)
    var status: String = "PENDING",   // PENDING | APPLIED | REJECTED | SUPERSEDED

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "reviewed_by", columnDefinition = "uuid")
    var reviewedBy: UUID? = null,

    @Column(name = "reviewed_at", columnDefinition = "timestamp with time zone")
    var reviewedAt: Instant? = null,

    @Column(name = "review_note", length = 500)
    var reviewNote: String? = null
)

@Repository
interface ProductCorrectionProposalRepository : JpaRepository<ProductCorrectionProposalEntity, UUID> {
    @Query("SELECT p FROM ProductCorrectionProposalEntity p WHERE p.status = 'PENDING' ORDER BY p.createdAt ASC")
    fun findPending(): List<ProductCorrectionProposalEntity>
}
