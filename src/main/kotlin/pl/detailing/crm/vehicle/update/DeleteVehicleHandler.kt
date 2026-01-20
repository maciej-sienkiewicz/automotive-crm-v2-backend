package pl.detailing.crm.vehicle.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VehicleId
import pl.detailing.crm.shared.VehicleStatus
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.Instant

@Service
class DeleteVehicleHandler(
    private val vehicleRepository: VehicleRepository
) {

    @Transactional
    suspend fun handle(command: DeleteVehicleCommand) = withContext(Dispatchers.IO) {
        val vehicleEntity = vehicleRepository.findByIdAndStudioId(
            command.vehicleId.value,
            command.studioId.value
        ) ?: throw EntityNotFoundException("Vehicle not found with id: ${command.vehicleId}")

        // Soft delete - archive the vehicle
        vehicleEntity.status = VehicleStatus.ARCHIVED
        vehicleEntity.updatedBy = command.userId.value
        vehicleEntity.updatedAt = Instant.now()

        vehicleRepository.save(vehicleEntity)
    }
}

data class DeleteVehicleCommand(
    val vehicleId: VehicleId,
    val studioId: StudioId,
    val userId: UserId
)
