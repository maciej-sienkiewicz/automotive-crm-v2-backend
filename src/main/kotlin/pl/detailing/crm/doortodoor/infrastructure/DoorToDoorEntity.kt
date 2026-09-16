package pl.detailing.crm.doortodoor.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.doortodoor.domain.DoorToDoor
import pl.detailing.crm.doortodoor.domain.DoorToDoorAddress
import pl.detailing.crm.doortodoor.domain.DoorToDoorStatus
import pl.detailing.crm.shared.DoorToDoorId
import pl.detailing.crm.shared.EmployeeId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "door_to_door",
    indexes = [
        Index(name = "idx_d2d_studio_id", columnList = "studio_id"),
        Index(name = "idx_d2d_visit_id", columnList = "visit_id", unique = true)
    ]
)
class DoorToDoorEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "visit_id", nullable = false, columnDefinition = "uuid")
    val visitId: UUID,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean,

    @Column(name = "pickup_city", nullable = false, length = 255)
    var pickupCity: String,

    @Column(name = "pickup_street", nullable = false, length = 255)
    var pickupStreet: String,

    @Column(name = "delivery_city", nullable = false, length = 255)
    var deliveryCity: String,

    @Column(name = "delivery_street", nullable = false, length = 255)
    var deliveryStreet: String,

    @Column(name = "notes", length = 1000)
    var notes: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    var status: DoorToDoorStatus,

    /* Bez klucza obcego do employees, celowo: rekord D2D ma przeżyć usunięcie
       pracownika, a nazwisko i tak trzymamy w migawce obok. */
    @Column(name = "driver_id", columnDefinition = "uuid")
    var driverId: UUID?,

    @Column(name = "driver_name", length = 200)
    var driverName: String?,

    @Column(name = "scheduled_at")
    var scheduledAt: Instant?,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "updated_by", nullable = false, columnDefinition = "uuid")
    var updatedBy: UUID,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant
) {
    fun toDomain() = DoorToDoor(
        id = DoorToDoorId(id),
        studioId = StudioId(studioId),
        visitId = VisitId(visitId),
        enabled = enabled,
        pickupAddress = DoorToDoorAddress(city = pickupCity, street = pickupStreet),
        deliveryAddress = DoorToDoorAddress(city = deliveryCity, street = deliveryStreet),
        notes = notes,
        status = status,
        driverId = driverId?.let { EmployeeId(it) },
        driverName = driverName,
        scheduledAt = scheduledAt,
        createdBy = UserId(createdBy),
        updatedBy = UserId(updatedBy),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(domain: DoorToDoor) = DoorToDoorEntity(
            id = domain.id.value,
            studioId = domain.studioId.value,
            visitId = domain.visitId.value,
            enabled = domain.enabled,
            pickupCity = domain.pickupAddress.city,
            pickupStreet = domain.pickupAddress.street,
            deliveryCity = domain.deliveryAddress.city,
            deliveryStreet = domain.deliveryAddress.street,
            notes = domain.notes,
            status = domain.status,
            driverId = domain.driverId?.value,
            driverName = domain.driverName,
            scheduledAt = domain.scheduledAt,
            createdBy = domain.createdBy.value,
            updatedBy = domain.updatedBy.value,
            createdAt = domain.createdAt,
            updatedAt = domain.updatedAt
        )
    }
}
