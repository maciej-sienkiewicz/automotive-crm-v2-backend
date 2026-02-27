package pl.detailing.crm.statistics.category.domain

import pl.detailing.crm.shared.ServiceCategoryId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant

/**
 * ServiceCategory - Business Dimension for grouping services into analytical units.
 *
 * Categories are multi-tenant: each studio has its own isolated set of categories.
 * Each category stores root service IDs (oldest ancestor in a version chain),
 * ensuring that historical and future versions are all captured in statistics.
 */
data class ServiceCategory(
    val id: ServiceCategoryId,
    val studioId: StudioId,
    val name: String,
    val description: String?,
    val color: String?,
    val isActive: Boolean,
    val createdBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
)
