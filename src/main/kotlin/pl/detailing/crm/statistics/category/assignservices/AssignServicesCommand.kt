package pl.detailing.crm.statistics.category.assignservices

import pl.detailing.crm.shared.ServiceCategoryId
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class AssignServicesCommand(
    val categoryId: ServiceCategoryId,
    val studioId: StudioId,
    val requestedBy: UserId,
    /**
     * Full replacement: these service IDs will be the ONLY assignments for the category.
     * Any previously assigned services not in this list are removed.
     * An empty list removes all assignments.
     */
    val serviceIds: List<ServiceId>
)
