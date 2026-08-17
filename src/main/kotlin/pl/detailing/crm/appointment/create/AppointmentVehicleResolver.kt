package pl.detailing.crm.appointment.create

import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.domain.VehicleOwner
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerEntity
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.Instant

/**
 * Vehicle-side effects of appointment create/update that go beyond the appointment itself:
 * plate-collision detection for NEW vehicles and ownership resolution for LINK_EXISTING.
 *
 * Shared by [CreateAppointmentHandler] and the update handler so the collision/ownership
 * rules cannot drift between the two paths. Runs inside the caller's transaction.
 */
@Service
class AppointmentVehicleResolver(
    private val vehicleRepository: VehicleRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val customerRepository: CustomerRepository,
    private val auditService: AuditService
) {

    /**
     * Rejects creating a duplicate of an existing active vehicle (same normalized plate)
     * with 409 VEHICLE_PLATE_EXISTS — unless the user explicitly dismissed exactly this
     * collision via [VehicleIdentity.New.duplicateOverrideVehicleId].
     */
    fun ensureNoPlateCollision(identity: VehicleIdentity.New, studioId: StudioId) {
        val plate = identity.licensePlate?.trim().orEmpty()
        if (plate.isBlank()) return

        val normalized = normalizePlate(plate)
        val colliding = vehicleRepository.findActiveByStudioId(studioId.value)
            .firstOrNull { normalizePlate(it.licensePlate.orEmpty()) == normalized }
            ?: return

        if (identity.duplicateOverrideVehicleId?.value == colliding.id) return

        val primaryOwnerName = vehicleOwnerRepository.findPrimaryOwnerByVehicleId(colliding.id)
            ?.let { owner -> customerRepository.findById(owner.id.customerId).orElse(null) }
            ?.let { customer -> listOfNotNull(customer.firstName, customer.lastName).joinToString(" ").ifBlank { null } }

        throw VehiclePlateExistsException(
            vehicleId = colliding.id.toString(),
            brand = colliding.brand,
            model = colliding.model,
            year = colliding.yearOfProduction,
            licensePlate = colliding.licensePlate,
            primaryOwnerName = primaryOwnerName
        )
    }

    /**
     * Attaches an existing vehicle to [customerId] according to the requested ownership action
     * and returns the vehicle id to bind to the appointment.
     */
    fun linkExisting(
        identity: VehicleIdentity.LinkExisting,
        customerId: CustomerId,
        studioId: StudioId,
        userId: UserId,
        userName: String?
    ): VehicleId {
        val vehicleEntity = vehicleRepository.findByIdAndStudioId(identity.vehicleId.value, studioId.value)
            ?: throw EntityNotFoundException("Pojazd o ID '${identity.vehicleId}' nie został znaleziony w tym studiu")

        val owners = vehicleOwnerRepository.findByVehicleId(vehicleEntity.id)
        val existingLink = owners.firstOrNull { it.id.customerId == customerId.value }
        val displayName = listOfNotNull(vehicleEntity.brand, vehicleEntity.model, vehicleEntity.licensePlate)
            .joinToString(" ")

        when (identity.ownership) {
            VehicleOwnershipAction.ADD_CO_OWNER -> {
                // Already an owner in any role → nothing to change, linking is idempotent.
                if (existingLink == null) {
                    addOwner(vehicleEntity.id, customerId, OwnershipRole.CO_OWNER, studioId, userId, userName, displayName)
                }
            }
            VehicleOwnershipAction.TRANSFER_PRIMARY -> {
                owners
                    .filter { it.id.customerId != customerId.value }
                    .forEach { removeOwner(it, studioId, userId, userName, displayName) }
                if (existingLink != null) {
                    existingLink.ownershipRole = OwnershipRole.PRIMARY
                    vehicleOwnerRepository.save(existingLink)
                } else {
                    addOwner(vehicleEntity.id, customerId, OwnershipRole.PRIMARY, studioId, userId, userName, displayName)
                }
            }
        }

        return identity.vehicleId
    }

    private fun addOwner(
        vehicleId: java.util.UUID,
        customerId: CustomerId,
        role: OwnershipRole,
        studioId: StudioId,
        userId: UserId,
        userName: String?,
        vehicleDisplayName: String
    ) {
        vehicleOwnerRepository.save(VehicleOwnerEntity.fromDomain(VehicleOwner(
            vehicleId = VehicleId(vehicleId),
            customerId = customerId,
            ownershipRole = role,
            assignedAt = Instant.now()
        )))
        auditService.logSync(LogAuditCommand(
            studioId = studioId,
            userId = userId,
            userDisplayName = userName ?: "",
            module = AuditModule.VEHICLE,
            entityId = vehicleId.toString(),
            entityDisplayName = vehicleDisplayName,
            action = AuditAction.OWNER_ADDED,
            metadata = mapOf("customerId" to customerId.value.toString(), "role" to role.name)
        ))
    }

    private fun removeOwner(
        owner: VehicleOwnerEntity,
        studioId: StudioId,
        userId: UserId,
        userName: String?,
        vehicleDisplayName: String
    ) {
        vehicleOwnerRepository.delete(owner)
        auditService.logSync(LogAuditCommand(
            studioId = studioId,
            userId = userId,
            userDisplayName = userName ?: "",
            module = AuditModule.VEHICLE,
            entityId = owner.id.vehicleId.toString(),
            entityDisplayName = vehicleDisplayName,
            action = AuditAction.OWNER_REMOVED,
            metadata = mapOf("customerId" to owner.id.customerId.toString(), "reason" to "OWNERSHIP_TRANSFER")
        ))
    }

    companion object {
        fun normalizePlate(plate: String): String = plate.replace(WHITESPACE, "").uppercase()
        private val WHITESPACE = "\\s".toRegex()
    }
}
