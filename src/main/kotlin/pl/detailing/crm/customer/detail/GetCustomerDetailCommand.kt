package pl.detailing.crm.customer.detail

import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId

data class GetCustomerDetailCommand(
    val customerId: CustomerId,
    val studioId: StudioId
)
