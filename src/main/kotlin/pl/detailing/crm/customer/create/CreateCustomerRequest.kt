package pl.detailing.crm.customer.create

data class CreateCustomerRequest(
    val firstName: String,
    val lastName: String,
    val email: String,
    val phone: String,
    val homeAddress: HomeAddressRequest?,
    val companyData: CompanyDataRequest?,
    val notes: String?
)

data class HomeAddressRequest(
    val street: String,
    val city: String,
    val postalCode: String,
    val country: String
)

data class CompanyDataRequest(
    val name: String,
    val nip: String?,
    val regon: String?,
    val address: CompanyAddressRequest?
)

data class CompanyAddressRequest(
    val street: String,
    val city: String,
    val postalCode: String,
    val country: String
)
