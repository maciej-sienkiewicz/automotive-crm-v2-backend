package pl.detailing.crm.customer.vehicles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VehicleStatus
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository

@Service
class GetCustomerVehiclesHandler(
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val vehicleRepository: VehicleRepository
) {
    suspend fun handle(customerId: CustomerId, studioId: StudioId, includeDeleted: Boolean = false): List<VehicleResponse> =
        withContext(Dispatchers.IO) {
            val vehicleOwners = vehicleOwnerRepository.findByCustomerId(customerId.value)

            val vehicles = vehicleOwners.mapNotNull { owner ->
                vehicleRepository.findByIdAndStudioId(owner.id.vehicleId, studioId.value)
            }

            val filtered = if (includeDeleted) vehicles
                else vehicles.filter { it.status != VehicleStatus.ARCHIVED }

            filtered.map { vehicleEntity ->
                VehicleResponse(
                    id = vehicleEntity.id.toString(),
                    brand = vehicleEntity.brand,
                    model = vehicleEntity.model,
                    year = vehicleEntity.yearOfProduction,
                    licensePlate = vehicleEntity.licensePlate,
                    color = vehicleEntity.color,
                    active = vehicleEntity.status != VehicleStatus.ARCHIVED
                )
            }
        }
}

data class VehicleResponse(
    val id: String,
    val brand: String,
    val model: String,
    val year: Int?,
    val licensePlate: String?,
    val color: String?,
    val active: Boolean
)
