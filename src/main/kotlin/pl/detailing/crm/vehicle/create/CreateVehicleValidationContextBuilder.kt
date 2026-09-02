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

            val licensePlateExists = if (command.licensePlate != null) {
                val licensePlateExistsDeferred = async {
                    vehicleRepository.existsByStudioIdAndLicensePlate(
                        command.studioId.value,
                        command.licensePlate
                    )
                }
                licensePlateExistsDeferred.await()
            } else {
                false
            }

            // Every owner, not just the first — each lookup carries the studio filter.
            val owners = command.ownerIds
                .map { ownerId ->
                    async { customerRepository.findByIdAndStudioId(ownerId.value, command.studioId.value) }
                }
                .map { it.await() }

            CreateVehicleValidationContext(
                studioId = command.studioId,
                ownerIds = command.ownerIds,
                licensePlate = command.licensePlate,
                yearOfProduction = command.yearOfProduction,
                owners = owners,
                licensePlateExists = licensePlateExists
            )
        }
}
