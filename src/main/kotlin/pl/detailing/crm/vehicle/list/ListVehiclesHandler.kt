package pl.detailing.crm.vehicle.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.math.BigDecimal

@Service
class ListVehiclesHandler(
    private val vehicleRepository: VehicleRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val customerRepository: CustomerRepository
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

                VehicleListItem(
                    id = vehicleEntity.id.toString(),
                    licensePlate = vehicleEntity.licensePlate ?: "",
                    brand = vehicleEntity.brand,
                    model = vehicleEntity.model,
                    yearOfProduction = vehicleEntity.yearOfProduction,
                    owners = ownersInfo,
                    stats = VehicleStats(
                        totalVisits = 0,
                        lastVisitDate = null,
                        totalSpent = MoneyInfo(
                            netAmount = BigDecimal.ZERO,
                            grossAmount = BigDecimal.ZERO,
                            currency = "PLN"
                        ),
                        averageVisitCost = MoneyInfo(
                            netAmount = BigDecimal.ZERO,
                            grossAmount = BigDecimal.ZERO,
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
    val totalSpent: MoneyInfo,
    val averageVisitCost: MoneyInfo
)

data class MoneyInfo(
    val netAmount: BigDecimal,
    val grossAmount: BigDecimal,
    val currency: String
)
