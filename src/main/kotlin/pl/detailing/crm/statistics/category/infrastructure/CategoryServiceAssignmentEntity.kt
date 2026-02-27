package pl.detailing.crm.statistics.category.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Maps a service category to a service lineage root.
 *
 * IMPORTANT: Only the ROOT service ID (oldest ancestor, replaces_service_id IS NULL) is stored.
 * This design allows the statistics queries to traverse forward through all newer versions
 * via a recursive CTE, ensuring complete historical coverage without needing to
 * update assignments when a service is versioned.
 */
@Entity
@Table(
    name = "category_service_assignments",
    indexes = [
        Index(name = "idx_cat_svc_assign_category_id", columnList = "category_id"),
        Index(name = "idx_cat_svc_assign_service_id", columnList = "service_id"),
        Index(name = "idx_cat_svc_assign_studio_id", columnList = "studio_id"),
        Index(name = "idx_cat_svc_assign_category_studio", columnList = "category_id, studio_id")
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_category_service_assignment",
            columnNames = ["category_id", "service_id"]
        )
    ]
)
class CategoryServiceAssignmentEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "category_id", nullable = false, columnDefinition = "uuid")
    val categoryId: UUID,

    /**
     * Root service ID - the oldest ancestor in the version chain (replaces_service_id IS NULL).
     * Stats queries use a recursive CTE from this root to include all current and future versions.
     */
    @Column(name = "service_id", nullable = false, columnDefinition = "uuid")
    val serviceId: UUID,

    /**
     * Denormalized for fast multi-tenant query isolation.
     */
    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "assigned_at", nullable = false, columnDefinition = "timestamp with time zone")
    val assignedAt: Instant = Instant.now()
)
