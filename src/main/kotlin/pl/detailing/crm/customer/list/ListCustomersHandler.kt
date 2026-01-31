package pl.detailing.crm.customer.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.math.BigDecimal
import java.time.Instant

@Service
class ListCustomersHandler(
    private val customerRepository: CustomerRepository,
    private val visitRepository: VisitRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository
) {
    suspend fun handle(studioId: StudioId): List<CustomerListItem> =
        withContext(Dispatchers.IO) {
            val customers = customerRepository.findActiveByStudioId(studioId.value)

            customers.map { entity ->
                // Calculate visit statistics
                val visits = visitRepository.findByCustomerIdAndStudioId(entity.id, studioId.value)
                val completedVisits = visits.filter { it.status == VisitStatus.COMPLETED }

                val totalVisits = completedVisits.size
                val lastVisitDate = completedVisits.maxByOrNull { it.scheduledDate }?.scheduledDate

                var totalNetAmount = 0L
                var totalGrossAmount = 0L

                completedVisits.forEach { visit ->
                    visit.serviceItems.forEach { serviceItem ->
                        totalNetAmount += serviceItem.finalPriceNet
                        totalGrossAmount += serviceItem.finalPriceGross
                    }
                }

                // Calculate vehicle count
                val vehicleOwners = vehicleOwnerRepository.findByCustomerId(entity.id)
                val vehicleCount = vehicleOwners.size

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
                    lastVisitDate = lastVisitDate,
                    totalVisits = totalVisits,
                    vehicleCount = vehicleCount,
                    totalRevenue = RevenueInfo(
                        netAmount = BigDecimal.valueOf(totalNetAmount).divide(BigDecimal.valueOf(100)),
                        grossAmount = BigDecimal.valueOf(totalGrossAmount).divide(BigDecimal.valueOf(100)),
                        currency = "PLN"
                    ),
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt
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
