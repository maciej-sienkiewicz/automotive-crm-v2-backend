package pl.detailing.crm.customer.update

import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class DeleteCompanyCommand(
    val customerId: CustomerId,
    val studioId: StudioId,
    val userId: UserId
)
