package pl.detailing.crm.statistics.category.manual

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Assigns a manual service (identified by its UUID from [ManualServiceEntity]) to a category.
 *
 * Enforces the one-category-per-manual-service invariant via the studio-scoped unique constraint.
 * Unlike [pl.detailing.crm.statistics.category.infrastructure.CategoryServiceAssignmentEntity],
 * there is no version-chain concept — manual services are identified by name, and each
 * (studio, name) pair maps to exactly one UUID that never changes.
 */
@Entity
@Table(
    name = "manual_service_category_assignments",
    indexes = [
        Index(name = "idx_manual_svc_cat_studio", columnList = "studio_id"),
        Index(name = "idx_manual_svc_cat_category", columnList = "category_id"),
        Index(name = "idx_manual_svc_cat_service", columnList = "manual_service_id")
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_manual_svc_category",
            columnNames = ["studio_id", "manual_service_id"]
        )
    ]
)
class ManualServiceCategoryAssignmentEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "manual_service_id", nullable = false, columnDefinition = "uuid")
    val manualServiceId: UUID,

    @Column(name = "category_id", nullable = false, columnDefinition = "uuid")
    val categoryId: UUID,

    @Column(name = "assigned_at", nullable = false, columnDefinition = "timestamp with time zone")
    val assignedAt: Instant = Instant.now()
)
