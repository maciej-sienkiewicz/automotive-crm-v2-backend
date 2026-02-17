package pl.detailing.crm.vehicle.owner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VehicleId
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerKey
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository

@Service
class RemoveOwnerHandler(
    private val vehicleRepository: VehicleRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val auditService: AuditService
) {

    @Transactional
    suspend fun handle(command: RemoveOwnerCommand) = withContext(Dispatchers.IO) {
        val vehicleEntity = vehicleRepository.findByIdAndStudioId(
            command.vehicleId.value,
            command.studioId.value
        ) ?: throw EntityNotFoundException("Vehicle not found with id: ${command.vehicleId}")

        val ownerKey = VehicleOwnerKey(
            vehicleId = command.vehicleId.value,
            customerId = command.customerId.value
        )

        val ownerEntity = vehicleOwnerRepository.findById(ownerKey).orElse(null)
            ?: throw EntityNotFoundException("Owner relationship not found")

        vehicleOwnerRepository.delete(ownerEntity)

        val displayName = listOfNotNull(vehicleEntity.brand, vehicleEntity.model, vehicleEntity.licensePlate).joinToString(" ")

        if (command.userId != null) {
            auditService.log(LogAuditCommand(
                studioId = command.studioId,
                userId = command.userId,
                userDisplayName = command.userName ?: "",
                module = AuditModule.VEHICLE,
                entityId = command.vehicleId.value.toString(),
                entityDisplayName = displayName,
                action = AuditAction.OWNER_REMOVED,
                metadata = mapOf("customerId" to command.customerId.value.toString())
            ))
        }
    }
}

data class RemoveOwnerCommand(
    val vehicleId: VehicleId,
    val studioId: StudioId,
    val userId: UserId? = null,
    val userName: String? = null,
    val customerId: CustomerId
)
