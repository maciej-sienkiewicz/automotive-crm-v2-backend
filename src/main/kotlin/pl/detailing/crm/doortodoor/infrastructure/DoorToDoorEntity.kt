package pl.detailing.crm.doortodoor.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.doortodoor.domain.DoorToDoor
import pl.detailing.crm.doortodoor.domain.DoorToDoorAddress
import pl.detailing.crm.doortodoor.domain.DoorToDoorStatus
import pl.detailing.crm.shared.DoorToDoorId
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
        pickupAddress = DoorToDoorAddress(city = pickupCity, street = pickupStreet),
        deliveryAddress = DoorToDoorAddress(city = deliveryCity, street = deliveryStreet),
        notes = notes,
        status = status,
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
            pickupCity = domain.pickupAddress.city,
            pickupStreet = domain.pickupAddress.street,
            deliveryCity = domain.deliveryAddress.city,
            deliveryStreet = domain.deliveryAddress.street,
            notes = domain.notes,
            status = domain.status,
            createdBy = domain.createdBy.value,
            updatedBy = domain.updatedBy.value,
            createdAt = domain.createdAt,
            updatedAt = domain.updatedAt
        )
    }
}
