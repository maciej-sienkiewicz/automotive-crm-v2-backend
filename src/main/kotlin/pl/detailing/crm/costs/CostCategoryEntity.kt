package pl.detailing.crm.costs

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "cost_categories",
    indexes = [
        Index(name = "idx_cost_categories_studio_id", columnList = "studio_id"),
        Index(name = "idx_cost_categories_studio_active", columnList = "studio_id, is_active"),
    ]
)
class CostCategoryEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "name", nullable = false, length = 200)
    var name: String,

    @Column(name = "description", columnDefinition = "TEXT")
    var description: String?,

    @Column(name = "color", length = 7)
    var color: String?,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
)
