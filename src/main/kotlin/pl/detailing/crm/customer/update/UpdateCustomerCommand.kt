package pl.detailing.crm.customer.update

import pl.detailing.crm.customer.domain.HomeAddress
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class UpdateCustomerCommand(
    val customerId: CustomerId,
    val studioId: StudioId,
    val userId: UserId,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phone: String,
    val homeAddress: HomeAddress?
)
