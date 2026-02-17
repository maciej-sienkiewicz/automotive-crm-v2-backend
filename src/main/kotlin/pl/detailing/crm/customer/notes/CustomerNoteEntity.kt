package pl.detailing.crm.customer.notes

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "customer_notes",
    indexes = [
        Index(name = "idx_customer_notes_customer_id", columnList = "customer_id"),
        Index(name = "idx_customer_notes_studio_id", columnList = "studio_id")
    ]
)
class CustomerNoteEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "customer_id", nullable = false, columnDefinition = "uuid")
    val customerId: UUID,

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    var content: String,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_by_name", nullable = false, length = 200)
    val createdByName: String,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
)
