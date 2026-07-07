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
import pl.detailing.crm.shared.pii.Pii
import java.time.Instant
import java.util.UUID

data class CustomerListQuery(
    val vehicleBrand: String? = null,
    val vehicleModel: String? = null,
    val serviceIds: List<UUID>? = null
)

@Service
class ListCustomersHandler(
    private val customerRepository: CustomerRepository,
    private val visitRepository: VisitRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository
) {
    suspend fun handle(studioId: StudioId, query: CustomerListQuery = CustomerListQuery()): List<CustomerListItem> =
        withContext(Dispatchers.IO) {
            val customerEntities = resolveCustomerEntities(studioId, query)

            customerEntities.map { entity ->
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

    private fun resolveCustomerEntities(studioId: StudioId, query: CustomerListQuery) =
        with(query) {
            val hasVehicleFilter = !vehicleBrand.isNullOrBlank() || !vehicleModel.isNullOrBlank()
            val hasServiceFilter = !serviceIds.isNullOrEmpty()

            if (!hasVehicleFilter && !hasServiceFilter) {
                return@with customerRepository.findActiveByStudioId(studioId.value)
            }

            var allowedIds: Set<UUID>? = null

            if (hasVehicleFilter) {
                val ids = vehicleOwnerRepository.findCustomerIdsByVehicleFilter(
                    studioId = studioId.value,
                    brand = vehicleBrand?.takeIf { it.isNotBlank() },
                    model = vehicleModel?.takeIf { it.isNotBlank() }
                ).toSet()
                allowedIds = allowedIds?.intersect(ids) ?: ids
            }

            if (hasServiceFilter) {
                val ids = visitRepository.findCustomerIdsByServiceIds(
                    studioId = studioId.value,
                    serviceIds = serviceIds!!
                ).toSet()
                allowedIds = allowedIds?.intersect(ids) ?: ids
            }

            val ids = allowedIds ?: emptySet()
            if (ids.isEmpty()) emptyList()
            else customerRepository.findActiveByStudioIdAndIds(studioId.value, ids.toList())
        }
}

data class CustomerListItem(
    val id: String,
    @Pii val firstName: String?,
    @Pii val lastName: String?,
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
    @Pii val email: String?,
    @Pii val phone: String?
)

data class HomeAddressInfo(
    @Pii val street: String,
    @Pii val city: String,
    @Pii val postalCode: String,
    val country: String
)

data class CompanyInfo(
    val id: String,
    val name: String,
    @Pii val nip: String?,
    val regon: String?,
    val address: CompanyAddressInfo?
)

data class CompanyAddressInfo(
    @Pii val street: String,
    @Pii val city: String,
    @Pii val postalCode: String,
    val country: String
)

data class RevenueInfo(
    val netAmount: BigDecimal,
    val grossAmount: BigDecimal,
    val currency: String
)
