package pl.detailing.crm.vehicle.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.audit.domain.*
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
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val auditService: AuditService,
    private val transactionTemplate: TransactionTemplate
) {

    @Transactional
    suspend fun handle(command: CreateVehicleCommand): CreateVehicleResult = withContext(Dispatchers.IO) {
        validatorComposite.validate(command)

        val vehicle = Vehicle(
            id = VehicleId.random(),
            studioId = command.studioId,
            licensePlate = command.licensePlate?.trim()?.uppercase(),
            brand = command.brand.trim(),
            model = command.model.trim(),
            yearOfProduction = command.yearOfProduction,
            color = command.color?.trim(),
            paintType = command.paintType?.trim(),
            currentMileage = command.currentMileage,
            status = VehicleStatus.ACTIVE,
            createdBy = command.userId,
            updatedBy = command.userId,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        // Vehicle and its owners in ONE real transaction (TransactionTemplate — the body
        // of a `@Transactional suspend` function on Dispatchers.IO escapes the
        // interceptor-managed transaction; see AuditLogWriter). Otherwise a failure
        // while linking owners left an ownerless vehicle that still occupied the plate,
        // so the user could not simply add the car again.
        transactionTemplate.execute {
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
        }

        val displayName = listOfNotNull(vehicle.brand, vehicle.model, vehicle.licensePlate).joinToString(" ")

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.VEHICLE,
            entityId = vehicle.id.value.toString(),
            entityDisplayName = displayName,
            action = AuditAction.CREATE,
            changes = listOfNotNull(
                vehicle.licensePlate?.let { FieldChange("licensePlate", null, it) },
                FieldChange("brand", null, vehicle.brand),
                FieldChange("model", null, vehicle.model),
                vehicle.yearOfProduction?.let { FieldChange("yearOfProduction", null, it.toString()) },
                vehicle.color?.let { FieldChange("color", null, it) },
                vehicle.paintType?.let { FieldChange("paintType", null, it) },
                FieldChange("currentMileage", null, vehicle.currentMileage.toString())
            )
        ))

        CreateVehicleResult(
            vehicleId = vehicle.id,
            licensePlate = vehicle.licensePlate,
            brand = vehicle.brand,
            model = vehicle.model,
            yearOfProduction = vehicle.yearOfProduction,
            color = vehicle.color,
            paintType = vehicle.paintType,
            currentMileage = vehicle.currentMileage,
            status = vehicle.status,
            ownerIds = command.ownerIds
        )
    }
}

data class CreateVehicleResult(
    val vehicleId: VehicleId,
    val licensePlate: String?,
    val brand: String,
    val model: String,
    val yearOfProduction: Int?,
    val color: String?,
    val paintType: String?,
    val currentMileage: Int,
    val status: VehicleStatus,
    val ownerIds: List<CustomerId>
)
