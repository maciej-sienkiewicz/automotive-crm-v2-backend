package pl.detailing.crm.customer.create

import pl.detailing.crm.customer.domain.CompanyData
import pl.detailing.crm.customer.domain.HomeAddress
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class CreateCustomerCommand(
    val studioId: StudioId,
    val userId: UserId,
    val firstName: String?,
    val lastName: String?,
    val email: String?,
    val phone: String?,
    val homeAddress: HomeAddress?,
    val companyData: CompanyData?,
    val notes: String?
)
