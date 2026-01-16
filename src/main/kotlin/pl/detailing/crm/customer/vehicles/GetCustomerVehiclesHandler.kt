package pl.detailing.crm.customer.vehicles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository

@Service
class GetCustomerVehiclesHandler(
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val vehicleRepository: VehicleRepository
) {
    suspend fun handle(customerId: CustomerId, studioId: StudioId): List<VehicleResponse> =
        withContext(Dispatchers.IO) {
            // Find all vehicles owned by this customer
            val vehicleOwners = vehicleOwnerRepository.findByCustomerId(customerId.value)

            // Get vehicle details for each vehicle
            val vehicles = vehicleOwners.mapNotNull { owner ->
                vehicleRepository.findByIdAndStudioId(owner.id.vehicleId, studioId.value)
            }

            // Map to response DTOs
            vehicles.map { vehicleEntity ->
                VehicleResponse(
                    id = vehicleEntity.id.toString(),
                    brand = vehicleEntity.brand,
                    model = vehicleEntity.model,
                    year = vehicleEntity.yearOfProduction,
                    licensePlate = vehicleEntity.licensePlate
                )
            }
        }
}

data class VehicleResponse(
    val id: String,
    val brand: String,
    val model: String,
    val year: Int,
    val licensePlate: String
)
