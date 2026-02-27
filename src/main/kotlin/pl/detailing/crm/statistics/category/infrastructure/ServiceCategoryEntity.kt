package pl.detailing.crm.statistics.category.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.shared.ServiceCategoryId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.statistics.category.domain.ServiceCategory
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "service_categories",
    indexes = [
        Index(name = "idx_service_categories_studio_id", columnList = "studio_id"),
        Index(name = "idx_service_categories_studio_active", columnList = "studio_id, is_active"),
        Index(name = "idx_service_categories_studio_name", columnList = "studio_id, name"),
        Index(name = "idx_service_categories_created_by", columnList = "created_by")
    ]
)
class ServiceCategoryEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "name", nullable = false, length = 200)
    var name: String,

    @Column(name = "description", columnDefinition = "TEXT")
    var description: String?,

    /**
     * Optional hex color (#RRGGBB) for UI display of this category
     */
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
) {
    fun toDomain(): ServiceCategory = ServiceCategory(
        id = ServiceCategoryId(id),
        studioId = StudioId(studioId),
        name = name,
        description = description,
        color = color,
        isActive = isActive,
        createdBy = UserId(createdBy),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(category: ServiceCategory): ServiceCategoryEntity = ServiceCategoryEntity(
            id = category.id.value,
            studioId = category.studioId.value,
            name = category.name,
            description = category.description,
            color = category.color,
            isActive = category.isActive,
            createdBy = category.createdBy.value,
            createdAt = category.createdAt,
            updatedAt = category.updatedAt
        )
    }
}
