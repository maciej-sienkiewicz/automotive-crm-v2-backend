package pl.detailing.crm.customer.get

import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId

data class GetCustomerByIdCommand(
    val customerId: CustomerId,
    val studioId: StudioId
)
