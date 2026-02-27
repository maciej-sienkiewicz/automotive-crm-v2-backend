package pl.detailing.crm.statistics.category.update

import pl.detailing.crm.shared.ServiceCategoryId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class UpdateCategoryCommand(
    val categoryId: ServiceCategoryId,
    val studioId: StudioId,
    val updatedBy: UserId,
    val name: String,
    val description: String?,
    val color: String?
)
