package pl.detailing.crm.batchorder.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A position in the batch-orders service catalog.
 *
 * The catalog exists to stop operators retyping the same service (and guessing its
 * price) on every entry. It is a suggestion source and nothing more: an entry copies
 * name and amounts into its own `ServiceItemEmbeddable` at save time, so editing a
 * position here changes what the next entry is offered, never what an existing one
 * says. That is the whole reason there is no foreign key from the entry side.
 */
@Entity
@Table(
    name = "batch_order_services",
    indexes = [Index(name = "idx_batch_order_services_studio", columnList = "studio_id, is_active")]
)
class BatchOrderServiceEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "name", nullable = false, length = 500)
    var name: String,

    @Column(name = "net_amount_cents", nullable = false)
    var netAmountCents: Long = 0,

    @Column(name = "gross_amount_cents", nullable = false)
    var grossAmountCents: Long = 0,

    @Column(name = "vat_rate", nullable = false)
    var vatRate: Int = 23,

    /**
     * Soft delete. A removed position vanishes from suggestions but keeps its row —
     * "nie mogą wpłynąć na historyczne dane" is already guaranteed by the entry-side
     * snapshot, and keeping the row means a name can be resurrected rather than
     * silently colliding with the unique index.
     */
    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
)
