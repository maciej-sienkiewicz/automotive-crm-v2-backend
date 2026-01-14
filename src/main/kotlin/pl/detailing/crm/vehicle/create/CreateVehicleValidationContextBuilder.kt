package pl.detailing.crm.vehicle.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository

@Component
class CreateVehicleValidationContextBuilder(
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository
) {
    suspend fun build(command: CreateVehicleCommand): CreateVehicleValidationContext =
        withContext(Dispatchers.IO) {

            val vinExistsDeferred = async {
                if (command.vin != null) {
                    vehicleRepository.existsByStudioIdAndVin(
                        command.studioId.value,
                        command.vin
                    )
                } else {
                    false
                }
            }

            val licensePlateExistsDeferred = async {
                vehicleRepository.existsByStudioIdAndLicensePlate(
                    command.studioId.value,
                    command.licensePlate
                )
            }

            CreateVehicleValidationContext(
                studioId = command.studioId,
                ownerIds = command.ownerIds,
                licensePlate = command.licensePlate,
                vin = command.vin,
                yearOfProduction = command.yearOfProduction,
                customerExists = null,
                vinExists = vinExistsDeferred.await(),
                licensePlateExists = licensePlateExistsDeferred.await()
            )
        }
}
