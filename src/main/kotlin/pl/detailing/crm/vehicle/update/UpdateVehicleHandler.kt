package pl.detailing.crm.vehicle.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.Instant

@Service
class UpdateVehicleHandler(
    private val vehicleRepository: VehicleRepository,
    private val auditService: AuditService
) {

    @Transactional
    suspend fun handle(command: UpdateVehicleCommand): UpdateVehicleResult = withContext(Dispatchers.IO) {
        val vehicleEntity = vehicleRepository.findByIdAndStudioId(
            command.vehicleId.value,
            command.studioId.value
        ) ?: throw EntityNotFoundException("Vehicle not found with id: ${command.vehicleId}")

        // Capture old values for audit
        val oldValues = mapOf(
            "licensePlate" to vehicleEntity.licensePlate,
            "brand" to vehicleEntity.brand,
            "model" to vehicleEntity.model,
            "yearOfProduction" to vehicleEntity.yearOfProduction?.toString(),
            "color" to vehicleEntity.color,
            "paintType" to vehicleEntity.paintType,
            "currentMileage" to vehicleEntity.currentMileage.toString(),
            "status" to vehicleEntity.status.name
        )

        command.licensePlate?.let {
            vehicleEntity.licensePlate = it.trim().uppercase()
        }

        command.brand?.let {
            vehicleEntity.brand = it.trim()
        }

        command.model?.let {
            vehicleEntity.model = it.trim()
        }

        command.yearOfProduction?.let {
            vehicleEntity.yearOfProduction = it
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

        // Compute changes for audit
        val newValues = mapOf(
            "licensePlate" to vehicleEntity.licensePlate,
            "brand" to vehicleEntity.brand,
            "model" to vehicleEntity.model,
            "yearOfProduction" to vehicleEntity.yearOfProduction?.toString(),
            "color" to vehicleEntity.color,
            "paintType" to vehicleEntity.paintType,
            "currentMileage" to vehicleEntity.currentMileage.toString(),
            "status" to vehicleEntity.status.name
        )

        val changes = auditService.computeChanges(oldValues, newValues)
        val displayName = listOfNotNull(vehicleEntity.brand, vehicleEntity.model, vehicleEntity.licensePlate).joinToString(" ")

        if (changes.isNotEmpty()) {
            auditService.log(LogAuditCommand(
                studioId = command.studioId,
                userId = command.userId,
                userDisplayName = command.userName ?: "",
                module = AuditModule.VEHICLE,
                entityId = command.vehicleId.value.toString(),
                entityDisplayName = displayName,
                action = AuditAction.UPDATE,
                changes = changes
            ))
        }

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
    val userName: String? = null,
    val licensePlate: String?,
    val brand: String?,
    val model: String?,
    val yearOfProduction: Int?,
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
