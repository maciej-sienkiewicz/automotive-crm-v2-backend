package pl.detailing.crm.statistics.category.create

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class CreateCategoryCommand(
    val studioId: StudioId,
    val createdBy: UserId,
    val name: String,
    val description: String?,
    val color: String?
)
