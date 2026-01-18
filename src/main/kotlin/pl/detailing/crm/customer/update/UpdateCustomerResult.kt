package pl.detailing.crm.customer.update

import pl.detailing.crm.customer.domain.HomeAddress

data class UpdateCustomerResult(
    val id: String,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phone: String,
    val homeAddress: HomeAddress?,
    val updatedAt: String
)
