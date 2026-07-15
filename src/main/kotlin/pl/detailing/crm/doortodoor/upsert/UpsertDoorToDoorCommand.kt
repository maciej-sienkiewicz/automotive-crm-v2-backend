package pl.detailing.crm.doortodoor.upsert

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId

data class UpsertDoorToDoorCommand(
    val studioId: StudioId,
    val visitId: VisitId,
    val userId: UserId,
    val userName: String,
    val pickupCity: String,
    val pickupStreet: String,
    val deliveryCity: String,
    val deliveryStreet: String,
    val notes: String?
)
