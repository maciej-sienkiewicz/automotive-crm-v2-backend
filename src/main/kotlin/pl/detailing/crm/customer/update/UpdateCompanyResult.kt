package pl.detailing.crm.customer.update

import pl.detailing.crm.customer.domain.CompanyAddress

data class UpdateCompanyResult(
    val id: String,
    val name: String,
    val nip: String,
    val regon: String,
    val address: CompanyAddress
)
