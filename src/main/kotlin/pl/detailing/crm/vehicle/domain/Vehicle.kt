package pl.detailing.crm.vehicle.domain

import pl.detailing.crm.shared.*
import java.time.Instant

data class Vehicle(
    val id: VehicleId,
    val studioId: StudioId,
    val licensePlate: String,
    val brand: String,
    val model: String,
    val yearOfProduction: Int,
    val color: String?,
    val paintType: String?,
    val engineType: EngineType,
    val currentMileage: Int,
    val status: VehicleStatus,
    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class VehicleOwner(
    val vehicleId: VehicleId,
    val customerId: CustomerId,
    val ownershipRole: OwnershipRole,
    val assignedAt: Instant
)
