package pl.detailing.crm.product.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Notatka studia o produkcie. Nieskończenie wiele na produkt. Wzorzec audytu 1:1
 * z `visit_comments`: autor, znacznik czasu, miękkie usunięcie, ślad edycji.
 *
 * Notatka bywa przypięta do wizyty ([visitId] != null) — wtedy widać ją i w karcie
 * produktu, i w karcie wizyty. To najczęstszy moment powstania: „na tym aucie wyszło
 * tak". Notatki są zawsze prywatne dla studia, bez opcji udostępnienia (mogą zawierać
 * dane osobowe klienta).
 */
@Entity
@Table(
    name = "product_notes",
    indexes = [
        Index(name = "idx_product_notes_lookup", columnList = "studio_id, product_id, created_at")
    ]
)
class ProductNoteEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "product_id", nullable = false, columnDefinition = "uuid")
    val productId: UUID,

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    var content: String,

    @Column(name = "visit_id", columnDefinition = "uuid")
    val visitId: UUID?,

    @Column(name = "is_deleted", nullable = false)
    var isDeleted: Boolean = false,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_by_name", nullable = false, length = 200)
    val createdByName: String,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_by", columnDefinition = "uuid")
    var updatedBy: UUID? = null,

    @Column(name = "updated_by_name", length = 200)
    var updatedByName: String? = null,

    @Column(name = "updated_at", columnDefinition = "timestamp with time zone")
    var updatedAt: Instant? = null,

    @Column(name = "deleted_by", columnDefinition = "uuid")
    var deletedBy: UUID? = null,

    @Column(name = "deleted_at", columnDefinition = "timestamp with time zone")
    var deletedAt: Instant? = null
)
