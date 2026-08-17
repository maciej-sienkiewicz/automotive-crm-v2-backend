package pl.detailing.crm.vehicle.lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.create.AppointmentVehicleResolver
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository

/**
 * Live duplicate detection for the "add new vehicle" form: matches an active vehicle
 * by normalized license plate (whitespace-insensitive, case-insensitive) and returns
 * just enough data to render the collision card.
 */
@Service
class LookupVehicleByPlateHandler(
    private val vehicleRepository: VehicleRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val customerRepository: CustomerRepository
) {

    suspend fun handle(studioId: StudioId, licensePlate: String): VehiclePlateLookupResponse =
        withContext(Dispatchers.IO) {
            val normalized = AppointmentVehicleResolver.normalizePlate(licensePlate)
            if (normalized.isBlank()) return@withContext VehiclePlateLookupResponse(found = false, vehicle = null)

            val match = vehicleRepository.findActiveByStudioId(studioId.value)
                .firstOrNull { AppointmentVehicleResolver.normalizePlate(it.licensePlate.orEmpty()) == normalized }
                ?: return@withContext VehiclePlateLookupResponse(found = false, vehicle = null)

            val owners = vehicleOwnerRepository.findByVehicleId(match.id)
            val customersById = customerRepository.findAllById(owners.map { it.id.customerId })
                .associateBy { it.id }

            VehiclePlateLookupResponse(
                found = true,
                vehicle = VehiclePlateLookupVehicle(
                    id = match.id.toString(),
                    brand = match.brand,
                    model = match.model,
                    year = match.yearOfProduction,
                    licensePlate = match.licensePlate,
                    owners = owners.map { owner ->
                        val customer = customersById[owner.id.customerId]
                        VehiclePlateLookupOwner(
                            customerId = owner.id.customerId.toString(),
                            name = customer
                                ?.let { listOfNotNull(it.firstName, it.lastName).joinToString(" ").ifBlank { null } }
                                ?: "Nieznany klient",
                            role = owner.ownershipRole.name
                        )
                    }
                )
            )
        }
}

data class VehiclePlateLookupResponse(
    val found: Boolean,
    val vehicle: VehiclePlateLookupVehicle?
)

data class VehiclePlateLookupVehicle(
    val id: String,
    val brand: String,
    val model: String,
    val year: Int?,
    val licensePlate: String?,
    val owners: List<VehiclePlateLookupOwner>
)

data class VehiclePlateLookupOwner(
    val customerId: String,
    val name: String,
    val role: String
)
