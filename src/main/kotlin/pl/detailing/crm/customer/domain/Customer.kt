package pl.detailing.crm.customer.domain

import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant

data class Customer(
    val id: CustomerId,
    val studioId: StudioId,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phone: String,
    val homeAddress: HomeAddress?,
    val companyData: CompanyData?,
    val notes: String?,
    val isActive: Boolean,
    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class HomeAddress(
    val street: String,
    val city: String,
    val postalCode: String,
    val country: String
)

data class CompanyData(
    val name: String,
    val nip: String?,
    val regon: String?,
    val address: CompanyAddress?
)

data class CompanyAddress(
    val street: String,
    val city: String,
    val postalCode: String,
    val country: String
)
