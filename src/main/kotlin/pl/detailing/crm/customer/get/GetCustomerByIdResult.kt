package pl.detailing.crm.customer.get

import pl.detailing.crm.customer.domain.CompanyAddress
import pl.detailing.crm.customer.domain.CompanyData
import pl.detailing.crm.customer.domain.HomeAddress
import java.math.BigDecimal
import java.time.Instant

data class GetCustomerByIdResult(
    val id: String,
    val firstName: String,
    val lastName: String,
    val contact: ContactInfo,
    val homeAddress: HomeAddress?,
    val company: CompanyDetails?,
    val notes: String,
    val lastVisitDate: Instant?,
    val totalVisits: Int,
    val vehicleCount: Int,
    val totalRevenue: RevenueInfo,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class ContactInfo(
    val email: String,
    val phone: String
)

data class CompanyDetails(
    val id: String,
    val name: String,
    val nip: String?,
    val regon: String?,
    val address: CompanyAddress?
)

data class RevenueInfo(
    val netAmount: BigDecimal,
    val grossAmount: BigDecimal,
    val currency: String
)
