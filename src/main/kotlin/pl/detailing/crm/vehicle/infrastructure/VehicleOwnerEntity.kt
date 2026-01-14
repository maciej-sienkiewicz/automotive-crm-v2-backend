package pl.detailing.crm.vehicle.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.OwnershipRole
import pl.detailing.crm.shared.VehicleId
import pl.detailing.crm.vehicle.domain.VehicleOwner
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Embeddable
data class VehicleOwnerKey(
    @Column(name = "vehicle_id", columnDefinition = "uuid")
    val vehicleId: UUID,

    @Column(name = "customer_id", columnDefinition = "uuid")
    val customerId: UUID
) : Serializable

@Entity
@Table(
    name = "vehicle_owners",
    indexes = [
        Index(name = "idx_vehicle_owners_vehicle", columnList = "vehicle_id"),
        Index(name = "idx_vehicle_owners_customer", columnList = "customer_id")
    ]
)
class VehicleOwnerEntity(
    @EmbeddedId
    val id: VehicleOwnerKey,

    @Column(name = "ownership_role", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var ownershipRole: OwnershipRole,

    @Column(name = "assigned_at", nullable = false, columnDefinition = "timestamp with time zone")
    val assignedAt: Instant = Instant.now()
) {
    fun toDomain(): VehicleOwner = VehicleOwner(
        vehicleId = VehicleId(id.vehicleId),
        customerId = CustomerId(id.customerId),
        ownershipRole = ownershipRole,
        assignedAt = assignedAt
    )

    companion object {
        fun fromDomain(vehicleOwner: VehicleOwner): VehicleOwnerEntity = VehicleOwnerEntity(
            id = VehicleOwnerKey(
                vehicleId = vehicleOwner.vehicleId.value,
                customerId = vehicleOwner.customerId.value
            ),
            ownershipRole = vehicleOwner.ownershipRole,
            assignedAt = vehicleOwner.assignedAt
        )
    }
}
