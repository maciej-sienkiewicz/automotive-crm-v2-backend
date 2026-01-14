package pl.detailing.crm.customer.create

import pl.detailing.crm.customer.domain.CompanyData
import pl.detailing.crm.shared.StudioId

data class CreateCustomerValidationContext(
    val studioId: StudioId,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phone: String,
    val companyData: CompanyData?,
    val emailExists: Boolean,
    val phoneExists: Boolean
)
