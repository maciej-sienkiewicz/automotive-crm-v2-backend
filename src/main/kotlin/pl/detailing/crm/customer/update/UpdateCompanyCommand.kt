package pl.detailing.crm.customer.update

import pl.detailing.crm.customer.domain.CompanyAddress
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class UpdateCompanyCommand(
    val customerId: CustomerId,
    val studioId: StudioId,
    val userId: UserId,
    val name: String,
    val nip: String,
    val regon: String,
    val address: CompanyAddress
)
