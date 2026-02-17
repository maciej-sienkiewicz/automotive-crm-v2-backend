package pl.detailing.crm.vehicle.owner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.domain.VehicleOwner
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerEntity
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.Instant

@Service
class AssignOwnerHandler(
    private val vehicleRepository: VehicleRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val auditService: AuditService
) {

    @Transactional
    suspend fun handle(command: AssignOwnerCommand): AssignOwnerResult = withContext(Dispatchers.IO) {
        val vehicleEntity = vehicleRepository.findByIdAndStudioId(
            command.vehicleId.value,
            command.studioId.value
        ) ?: throw EntityNotFoundException("Vehicle not found with id: ${command.vehicleId}")

        // Check if owner already assigned
        val alreadyAssigned = vehicleOwnerRepository.existsByVehicleIdAndCustomerId(
            command.vehicleId.value,
            command.customerId.value
        )

        if (alreadyAssigned) {
            throw ValidationException("Customer is already assigned as an owner to this vehicle")
        }

        val vehicleOwner = VehicleOwner(
            vehicleId = command.vehicleId,
            customerId = command.customerId,
            ownershipRole = command.role,
            assignedAt = Instant.now()
        )

        val vehicleOwnerEntity = VehicleOwnerEntity.fromDomain(vehicleOwner)
        vehicleOwnerRepository.save(vehicleOwnerEntity)

        val displayName = listOfNotNull(vehicleEntity.brand, vehicleEntity.model, vehicleEntity.licensePlate).joinToString(" ")

        if (command.userId != null) {
            auditService.log(LogAuditCommand(
                studioId = command.studioId,
                userId = command.userId,
                userDisplayName = command.userName ?: "",
                module = AuditModule.VEHICLE,
                entityId = command.vehicleId.value.toString(),
                entityDisplayName = displayName,
                action = AuditAction.OWNER_ADDED,
                metadata = mapOf(
                    "customerId" to command.customerId.value.toString(),
                    "role" to command.role.name
                )
            ))
        }

        AssignOwnerResult(
            vehicleId = vehicleEntity.id.toString(),
            customerId = command.customerId.toString(),
            role = command.role.name,
            assignedAt = vehicleOwner.assignedAt
        )
    }
}

data class AssignOwnerCommand(
    val vehicleId: VehicleId,
    val studioId: StudioId,
    val userId: UserId? = null,
    val userName: String? = null,
    val customerId: CustomerId,
    val role: OwnershipRole
)

data class AssignOwnerResult(
    val vehicleId: String,
    val customerId: String,
    val role: String,
    val assignedAt: Instant
)
