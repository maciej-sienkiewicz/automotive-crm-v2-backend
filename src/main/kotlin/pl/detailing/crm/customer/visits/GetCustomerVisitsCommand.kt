package pl.detailing.crm.customer.visits

import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId

data class GetCustomerVisitsCommand(
    val customerId: CustomerId,
    val studioId: StudioId,
    val page: Int = 1,
    val limit: Int = 10
)
