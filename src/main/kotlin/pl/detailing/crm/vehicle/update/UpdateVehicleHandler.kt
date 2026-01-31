package pl.detailing.crm.vehicle.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.Instant

@Service
class UpdateVehicleHandler(
    private val vehicleRepository: VehicleRepository
) {

    @Transactional
    suspend fun handle(command: UpdateVehicleCommand): UpdateVehicleResult = withContext(Dispatchers.IO) {
        val vehicleEntity = vehicleRepository.findByIdAndStudioId(
            command.vehicleId.value,
            command.studioId.value
        ) ?: throw EntityNotFoundException("Vehicle not found with id: ${command.vehicleId}")

        command.licensePlate?.let {
            vehicleEntity.licensePlate = it.trim().uppercase()
        }

        command.color?.let {
            vehicleEntity.color = it.trim()
        }

        command.paintType?.let {
            vehicleEntity.paintType = it.trim()
        }

        command.currentMileage?.let {
            vehicleEntity.currentMileage = it
        }

        command.status?.let {
            vehicleEntity.status = it
        }

        vehicleEntity.updatedBy = command.userId.value
        vehicleEntity.updatedAt = Instant.now()

        vehicleRepository.save(vehicleEntity)

        UpdateVehicleResult(
            id = vehicleEntity.id.toString(),
            licensePlate = vehicleEntity.licensePlate,
            brand = vehicleEntity.brand,
            model = vehicleEntity.model,
            yearOfProduction = vehicleEntity.yearOfProduction,
            color = vehicleEntity.color,
            paintType = vehicleEntity.paintType,
            currentMileage = vehicleEntity.currentMileage.toLong(),
            status = vehicleEntity.status.name.lowercase(),
            updatedAt = vehicleEntity.updatedAt
        )
    }
}

data class UpdateVehicleCommand(
    val vehicleId: VehicleId,
    val studioId: StudioId,
    val userId: UserId,
    val licensePlate: String?,
    val color: String?,
    val paintType: String?,
    val currentMileage: Int?,
    val status: VehicleStatus?
)

data class UpdateVehicleResult(
    val id: String,
    val licensePlate: String?,
    val brand: String,
    val model: String,
    val yearOfProduction: Int?,
    val color: String?,
    val paintType: String?,
    val currentMileage: Long,
    val status: String,
    val updatedAt: Instant
)
