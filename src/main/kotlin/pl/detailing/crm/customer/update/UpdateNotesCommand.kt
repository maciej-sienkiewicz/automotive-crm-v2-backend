package pl.detailing.crm.customer.update

import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class UpdateNotesCommand(
    val customerId: CustomerId,
    val studioId: StudioId,
    val userId: UserId,
    val notes: String
)
