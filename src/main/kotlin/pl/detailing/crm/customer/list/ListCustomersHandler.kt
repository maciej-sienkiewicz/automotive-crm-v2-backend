package pl.detailing.crm.customer.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.StudioId
import java.math.BigDecimal

@Service
class ListCustomersHandler(
    private val customerRepository: CustomerRepository
) {
    suspend fun handle(studioId: StudioId): List<CustomerListItem> =
        withContext(Dispatchers.IO) {
            val customers = customerRepository.findActiveByStudioId(studioId.value)

            customers.map { entity ->
                CustomerListItem(
                    id = entity.id.toString(),
                    firstName = entity.firstName,
                    lastName = entity.lastName,
                    contact = ContactInfo(
                        email = entity.email,
                        phone = entity.phone
                    ),
                    homeAddress = null,
                    company = if (entity.companyName != null) {
                        CompanyInfo(
                            id = entity.id.toString(),
                            name = entity.companyName!!,
                            nip = entity.companyNip,
                            regon = entity.companyRegon,
                            address = null
                        )
                    } else null,
                    notes = "",
                    lastVisitDate = null,
                    totalVisits = 0,
                    vehicleCount = 0,
                    totalRevenue = RevenueInfo(
                        netAmount = BigDecimal.ZERO,
                        grossAmount = BigDecimal.ZERO,
                        currency = "PLN"
                    ),
                    createdAt = entity.createdAt.toString(),
                    updatedAt = entity.updatedAt.toString()
                )
            }
        }
}

data class CustomerListItem(
    val id: String,
    val firstName: String,
    val lastName: String,
    val contact: ContactInfo,
    val homeAddress: HomeAddressInfo?,
    val company: CompanyInfo?,
    val notes: String,
    val lastVisitDate: String?,
    val totalVisits: Int,
    val vehicleCount: Int,
    val totalRevenue: RevenueInfo,
    val createdAt: String,
    val updatedAt: String
)

data class ContactInfo(
    val email: String,
    val phone: String
)

data class HomeAddressInfo(
    val street: String,
    val city: String,
    val postalCode: String,
    val country: String
)

data class CompanyInfo(
    val id: String,
    val name: String,
    val nip: String?,
    val regon: String?,
    val address: CompanyAddressInfo?
)

data class CompanyAddressInfo(
    val street: String,
    val city: String,
    val postalCode: String,
    val country: String
)

data class RevenueInfo(
    val netAmount: BigDecimal,
    val grossAmount: BigDecimal,
    val currency: String
)
