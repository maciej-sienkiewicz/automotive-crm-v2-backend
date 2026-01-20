package pl.detailing.crm.vehicle.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.math.BigDecimal

@Service
class ListVehiclesHandler(
    private val vehicleRepository: VehicleRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val customerRepository: CustomerRepository,
    private val visitRepository: VisitRepository
) {
    suspend fun handle(studioId: StudioId): List<VehicleListItem> =
        withContext(Dispatchers.IO) {
            val vehicles = vehicleRepository.findByStudioId(studioId.value)

            vehicles.map { vehicleEntity ->
                val owners = vehicleOwnerRepository.findByVehicleId(vehicleEntity.id)

                val ownersInfo = owners.map { ownerEntity ->
                    val customer = customerRepository.findById(ownerEntity.id.customerId).orElse(null)
                    VehicleOwnerInfo(
                        customerId = ownerEntity.id.customerId.toString(),
                        customerName = if (customer != null) "${customer.firstName} ${customer.lastName}" else "Unknown",
                        role = ownerEntity.ownershipRole.name,
                        assignedAt = ownerEntity.assignedAt.toString()
                    )
                }

                // Calculate statistics from completed visits
                val visits = visitRepository.findByVehicleIdAndStudioId(vehicleEntity.id, studioId.value)
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

                VehicleListItem(
                    id = vehicleEntity.id.toString(),
                    licensePlate = vehicleEntity.licensePlate ?: "",
                    brand = vehicleEntity.brand,
                    model = vehicleEntity.model,
                    yearOfProduction = vehicleEntity.yearOfProduction,
                    owners = ownersInfo,
                    stats = VehicleStats(
                        totalVisits = totalVisits,
                        lastVisitDate = lastVisitDate?.toString(),
                        totalSpent = MoneyInfo(
                            netAmount = BigDecimal.valueOf(totalNetAmount).divide(BigDecimal.valueOf(100)),
                            grossAmount = BigDecimal.valueOf(totalGrossAmount).divide(BigDecimal.valueOf(100)),
                            currency = "PLN"
                        )
                    ),
                    status = vehicleEntity.status.name.lowercase()
                )
            }
        }
}

data class VehicleListItem(
    val id: String,
    val licensePlate: String,
    val brand: String,
    val model: String,
    val yearOfProduction: Int?,
    val owners: List<VehicleOwnerInfo>,
    val stats: VehicleStats,
    val status: String
)

data class VehicleOwnerInfo(
    val customerId: String,
    val customerName: String,
    val role: String,
    val assignedAt: String
)

data class VehicleStats(
    val totalVisits: Int,
    val lastVisitDate: String?,
    val totalSpent: MoneyInfo
)

data class MoneyInfo(
    val netAmount: BigDecimal,
    val grossAmount: BigDecimal,
    val currency: String
)
