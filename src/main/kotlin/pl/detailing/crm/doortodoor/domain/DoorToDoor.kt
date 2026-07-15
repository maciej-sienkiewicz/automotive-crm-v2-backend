package pl.detailing.crm.doortodoor.domain

import pl.detailing.crm.shared.DoorToDoorId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import java.time.Instant

enum class DoorToDoorStatus {
    SCHEDULED,
    IN_PICKUP,
    PICKED_UP,
    IN_DELIVERY,
    DELIVERED
}

data class DoorToDoorAddress(
    val city: String,
    val street: String
)

data class DoorToDoor(
    val id: DoorToDoorId,
    val studioId: StudioId,
    val visitId: VisitId,
    val pickupAddress: DoorToDoorAddress,
    val deliveryAddress: DoorToDoorAddress,
    val notes: String?,
    val status: DoorToDoorStatus,
    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
)
