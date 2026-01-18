package pl.detailing.crm.vehicle.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.domain.Vehicle
import pl.detailing.crm.vehicle.domain.VehicleOwner
import pl.detailing.crm.vehicle.infrastructure.VehicleEntity
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerEntity
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.Instant

@Service
class CreateVehicleHandler(
    private val validatorComposite: CreateVehicleValidatorComposite,
    private val vehicleRepository: VehicleRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository
) {

    @Transactional
    suspend fun handle(command: CreateVehicleCommand): CreateVehicleResult = withContext(Dispatchers.IO) {
        validatorComposite.validate(command)

        val vehicle = Vehicle(
            id = VehicleId.random(),
            studioId = command.studioId,
            licensePlate = command.licensePlate.trim().uppercase(),
            brand = command.brand.trim(),
            model = command.model.trim(),
            yearOfProduction = command.yearOfProduction,
            color = command.color?.trim(),
            paintType = command.paintType?.trim(),
            engineType = command.engineType,
            currentMileage = command.currentMileage,
            status = VehicleStatus.ACTIVE,
            createdBy = command.userId,
            updatedBy = command.userId,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        val vehicleEntity = VehicleEntity.fromDomain(vehicle)
        vehicleRepository.save(vehicleEntity)

        command.ownerIds
            .forEach {
                val vehicleOwner = VehicleOwner(
                    vehicleId = vehicle.id,
                    customerId = it,
                    ownershipRole = OwnershipRole.PRIMARY,
                    assignedAt = Instant.now()
                )

                val vehicleOwnerEntity = VehicleOwnerEntity.fromDomain(vehicleOwner)
                vehicleOwnerRepository.save(vehicleOwnerEntity)
            }

        CreateVehicleResult(
            vehicleId = vehicle.id,
            licensePlate = vehicle.licensePlate,
            brand = vehicle.brand,
            model = vehicle.model,
            yearOfProduction = vehicle.yearOfProduction,
            color = vehicle.color,
            paintType = vehicle.paintType,
            engineType = vehicle.engineType,
            currentMileage = vehicle.currentMileage,
            status = vehicle.status,
            ownerIds = command.ownerIds
        )
    }
}

data class CreateVehicleResult(
    val vehicleId: VehicleId,
    val licensePlate: String,
    val brand: String,
    val model: String,
    val yearOfProduction: Int,
    val color: String?,
    val paintType: String?,
    val engineType: EngineType,
    val currentMileage: Int,
    val status: VehicleStatus,
    val ownerIds: List<CustomerId>
)
